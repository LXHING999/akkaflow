package com.kent.workflow

import akka.actor.Actor
import akka.pattern.{ ask, pipe }
import akka.actor.ActorLogging
import com.kent.coordinate.Coordinator
import akka.actor.ActorRef
import scala.concurrent.ExecutionContext.Implicits.global
import akka.actor.Terminated
import akka.actor.Props
import com.kent.workflow.WorkflowInfo.WStatus._
import akka.actor.PoisonPill
import scala.concurrent.duration._
import akka.util.Timeout
import com.kent.workflow.node.NodeInfo.Status._
import com.kent.main.Master
import com.kent.pub.Event._
import scala.util.Success
import scala.concurrent.Future
import scala.concurrent.Await
import org.json4s.Extraction
import org.json4s.jackson.JsonMethods
import org.json4s.DefaultFormats
import java.util.Date
import akka.actor.Cancellable
import com.kent.ddata.HaDataStorager._
import com.kent.pub.ActorTool
import com.kent.pub.DaemonActor

class WorkFlowManager extends DaemonActor{
  /**
   * 工作流信息
   * [wfName, workflowInfo]
   */
  var workflows: Map[String, WorkflowInfo] = Map()
  /**
   * 运行中的工作流实例actor集合
   * Map[WorflowInstance.id, [WorkfowInstance, workflowActorRef]]
   */
  var workflowActors: Map[String,Tuple2[WorkflowInstance,ActorRef]] = Map()
  /**
   * 等待队列
   */
  val waittingWorkflowInstance = scala.collection.mutable.ListBuffer[WorkflowInstance]()
  
  var coordinatorManager: ActorRef = _
  //调度器
  var scheduler: Cancellable = _
  /**
   * 初始化
   */
  //init()
  
    /**
   * 启动
   */
  def start(): Boolean = {
		  import com.kent.coordinate.Coordinator.Status._
		  /**
		   * 从等待队列中找到满足运行的工作流实例
		   */
		  def getSatisfiedWFIfromWaitingWFIs(): Option[WorkflowInstance] = {
		    for(wfi <- waittingWorkflowInstance){
		      val runningInstanceNum = workflowActors.map{case (x,(y,z)) => y}
		                              .filter { _.workflow.name == wfi.workflow.name }.size
		      if(runningInstanceNum < wfi.workflow.instanceLimit){
		        Thread.sleep(200)  //????
		        waittingWorkflowInstance -= wfi
		        Master.haDataStorager ! RemoveWWFI(wfi.id)
		        return Some(wfi)
		      }
		    }
		    None
		  }
		  
		  
      Master.logRecorder ! Info("WorkFlowManager",null,s"启动扫描...")
      this.scheduler = context.system.scheduler.schedule(200 millis, 200 millis){
		    val wfiOpt = getSatisfiedWFIfromWaitingWFIs()
		    if(!wfiOpt.isEmpty){
		      val wfi = wfiOpt.get
		      val wfActorRef = context.actorOf(Props(WorkflowActor(wfi)), wfi.actorName)
		    		Master.logRecorder ! Info("WorkflowManager", null, s"开始生成并执行工作流实例：${wfi.actorName}")
    				workflowActors = workflowActors + (wfi.id -> (wfi,wfActorRef))
    				Master.haDataStorager ! AddRWFI(wfi)
  	  			wfActorRef ! Start()
		    }
      }
    true
  }
  def stop():Boolean = {
    if(scheduler == null || scheduler.isCancelled) true else scheduler.cancel()
  }
  
  /**
   * 增
   */
  def add(content: String, isSaved: Boolean): ResponseData = {
    var wf:WorkflowInfo = null
    try {
    	wf = WorkflowInfo(content)      
    } catch{
      case e: Exception => e.printStackTrace()
      return ResponseData("fail","content解析错误", null)
    }
    add(wf, isSaved)
  }
  /**
   * 增
   */
  def add(wf: WorkflowInfo, isSaved: Boolean): ResponseData = {
    Master.logRecorder ! Info("WorkflowManager", null, s"添加工作流配置：${wf.name}")
		if(isSaved) Master.persistManager ! Save(wf)
		Master.haDataStorager ! AddWorkflow(wf)
		
		if(workflows.get(wf.name).isEmpty){
			workflows = workflows + (wf.name -> wf)
		  ResponseData("success",s"成功添加工作流${wf.name}", null)
		}else{
		  workflows = workflows + (wf.name -> wf)
		  ResponseData("success",s"成功替换工作流${wf.name}", null)
		}
  }
  /**
   * 删
   */
  def remove(name: String): ResponseData = {
    if(!workflows.get(name).isEmpty){
      Master.logRecorder ! Info("WorkflowManager", null, s"删除工作流：${name}")
    	Master.persistManager ! Delete(workflows(name))
    	Master.haDataStorager ! RemoveWorkflow(name)
    	workflows = workflows.filterNot {x => x._1 == name}.toMap
      ResponseData("success",s"成功删除工作流${name}", null)
    }else{
      ResponseData("fail",s"工作流${name}不存在", null)
    }
  }
  /**
   * 初始化，从数据库中获取workflows
   */
/*  def init(){
    import com.kent.main.Master._
    val isEnabled = config.getBoolean("workflow.mysql.is-enabled")
    if(isEnabled){
       val listF = (Master.persistManager ? Query("select name from workflow")).mapTo[List[List[String]]]
       listF.andThen{
         case Success(list) => list.map { x =>
           val wf = new WorkflowInfo(x(0))
           val wfF = (Master.persistManager ? Get(wf)).mapTo[Option[WorkflowInfo]]
           wfF.andThen{
             case Success(wfOpt) => 
             if(!wfOpt.isEmpty) add(wfOpt.get, false)
           }
         }
       }
    }
  }*/
  /**
   * 生成工作流实例并执行
   */
  def newAndExecute(wfName: String,params: Map[String, String]): Boolean = {
    if(workflows.get(wfName).isEmpty){
      Master.logRecorder ! Error("WorkflowManager", null, s"未找到名称为[${wfName}]的工作流")
      false
    } else {
    	val wfi = workflows(wfName).createInstance()
			wfi.parsedParams = params
			//把工作流实例加入到等待队列中
			waittingWorkflowInstance += wfi
			Master.haDataStorager ! AddWWFI(wfi)
			true      
    }
  }
  /**
   * 生成工作流实例并执行
   */
  def manualNewAndExecute(wfName: String,params: Map[String, String]): ResponseData = {
    if(workflows.get(wfName).isEmpty){
      ResponseData("fail",s"工作流${wfName}不存在", null)
    } else {
    	val wfi = workflows(wfName).createInstance()
			wfi.parsedParams = params
			//把工作流实例加入到等待队列中
			waittingWorkflowInstance += wfi
			Master.haDataStorager ! AddWWFI(wfi)
			ResponseData("success",s"已生成工作流实例,id:${wfi.id}", null)
    }
  }
  /**
   * 工作流实例完成后处理
   */
  def handleWorkFlowInstanceReply(wfInstance: WorkflowInstance):Boolean = {
    //剔除该完成的工作流实例
    val (_, af) = this.workflowActors.get(wfInstance.id).get
    this.workflowActors = this.workflowActors.filterKeys { _ != wfInstance.id }.toMap
    Master.haDataStorager ! RemoveRWFI(wfInstance.id)
    //根据状态发送邮件告警
    if(wfInstance.workflow.mailLevel.contains(wfInstance.status)){
    	Master.emailSender ! EmailMessage(wfInstance.workflow.mailReceivers,
    	                "workflow告警", 
    	                s"任务【${wfInstance.actorName}】执行状态：${wfInstance.status}")      
    }
    Thread.sleep(1000)
    Master.logRecorder ! Info("WorkflowInstance", wfInstance.id, s"工作流实例：${wfInstance.actorName}执行完毕，执行状态为：${wfInstance.status}")
    coordinatorManager ! WorkFlowExecuteResult(wfInstance.workflow.name, wfInstance.status)  
    true
  }
  /**
   * kill掉指定工作流实例
   */
  def killWorkFlowInstance(id: String): Future[ResponseData] = {
    if(!workflowActors.get(id).isEmpty){
    	val wfaRef = workflowActors(id)._2
    	val result = (wfaRef ? Kill()).mapTo[List[ActionExecuteResult]]
    	this.workflowActors = this.workflowActors.filterKeys { _ != id }.toMap
    	Master.haDataStorager ! RemoveRWFI(id)
    	val resultF = result.map { x => 
    	  implicit val formats = DefaultFormats
        val strTmp = JsonMethods.compact(Extraction.decompose(x))
    	  ResponseData("success",s"工作流[${id}]已被杀死", strTmp) 
    	}
    	resultF
    }else{
      Future(ResponseData("fail",s"[工作流实例：${id}]不存在，不能kill掉", null))
    }
  }
  /**
   * kill掉指定工作流（包含其所有的运行实例）
   */
  def killWorkFlow(wfName: String): Future[ResponseData] = {
    val result = workflowActors.filter(_._2._1 == wfName).map(x => killWorkFlowInstance(x._1)).toList
    val resultF = Future.sequence(result).map { x => 
      ResponseData("success",s"工作流名称[wfName]的所有实例已经被杀死", null) 
    }
   resultF 
  }
  /**
   * kill所有工作流
   */
  def killAllWorkFlow(): Future[ResponseData] = {
    val result = workflowActors.map(x => killWorkFlowInstance(x._1)).toList
    val resultF = Future.sequence(result).map { x => 
      ResponseData("success",s"所有工作流实例已经被杀死", null) 
      
    }
   resultF 
  }
  /**
   * 重跑指定的工作流实例
   */
  def reRun(wfiId: String): Future[ResponseData] = {
    val wf = new WorkflowInfo(null)
    val wfi = wf.createInstance()
    wfi.id = wfiId
    implicit val timeout = Timeout(20 seconds)
    val wfiF = (Master.persistManager ? Get(wfi)).mapTo[Option[WorkflowInstance]]
    wfiF.map { wfiOpt => 
      if(wfiOpt.isEmpty){
        ResponseData("fail", s"工作流实例[${wfiId}]不存在", null)
      }else{
        if(!workflowActors.get(wfiId).isEmpty){
          ResponseData("fail", s"工作流实例[${wfiId}]已经在重跑", null)
        }else{
          //重置
        	val wfi2 = wfiOpt.get
          wfi2.reset()
          //把工作流实例加入到等待队列中
        	val existWaitCnt = waittingWorkflowInstance.filter{ _.id == wfi2.id }.size
        	if(existWaitCnt >= 1){
        	  ResponseData("fail", s"工作流实例[${wfiId}]已经存在等待队列中", null)
        	}else{
        		waittingWorkflowInstance += wfi2
        		Master.haDataStorager ! AddWWFI(wfi)
        		ResponseData("success", s"工作流实例[${wfiId}]开始重跑", null)        	  
        	}
        }
      }
    }
  }
  /**
   * 获取等待队列信息
   */
  def getWaittingNodeInfo():ResponseData = {
    val wns = this.waittingWorkflowInstance.map { x => Map("wfid" -> x.id,"name" -> x.workflow.name) }.toList
    ResponseData("success","成功获取等待队列信息", wns)
  }
  /**
   * receive方法
   */
  def indivivalReceive: Actor.Receive = {
    case Start() => this.start()
    case Stop() => sender ! this.stop();context.stop(self)
    case AddWorkFlow(content) => sender ! this.add(content, true)
    case RemoveWorkFlow(name) => sender ! this.remove(name)
    case NewAndExecuteWorkFlowInstance(name, params) => this.newAndExecute(name, params)
    case ManualNewAndExecuteWorkFlowInstance(name, params) => sender ! this.manualNewAndExecute(name, params)
    case WorkFlowInstanceExecuteResult(wfi) => this.handleWorkFlowInstanceReply(wfi)
    case KillWorkFlowInstance(id) =>  this.killWorkFlowInstance(id) pipeTo sender
    case KllAllWorkFlow() => this.killAllWorkFlow() pipeTo sender
    case KillWorkFlow(wfName) => this.killWorkFlow(wfName) pipeTo sender
    case ReRunWorkflowInstance(wfiId: String) => this.reRun(wfiId) pipeTo sender
    case GetManagers(wfm, cm) => 
      coordinatorManager = cm
      context.watch(coordinatorManager)
    case GetWaittingInstances() => sender ! getWaittingNodeInfo()
    case Terminated(arf) => if(coordinatorManager == arf) log.warning("coordinatorManager actor挂掉了...")
  }
}


object WorkFlowManager{
  def apply(wfs: List[WorkflowInfo]):WorkFlowManager = {
    WorkFlowManager(wfs, null)
  }
  def apply(wfs: List[WorkflowInfo], waittingWIFs: List[WorkflowInstance]) = {
    val wfm = new WorkFlowManager;
    if(wfs != null){
    	wfm.workflows = wfs.map { x => x.name -> x }.toMap      
    }
    if(waittingWIFs != null){
    	wfm.waittingWorkflowInstance ++= waittingWIFs      
    }
    wfm
  }
  def apply(contents: Set[String]):WorkFlowManager = {
    WorkFlowManager(contents.map { WorkflowInfo(_) }.toList)
  }
}