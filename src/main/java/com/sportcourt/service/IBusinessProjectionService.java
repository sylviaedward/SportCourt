package com.sportcourt.service;

import com.sportcourt.entity.BusinessOutboxTask;
import com.sportcourt.messaging.ProjectionResult;

/** 将持久业务事实投影到缓存、资源或后续任务。调用方负责消息身份与消费事务。 */
public interface IBusinessProjectionService {
  /** CONTINUE 表示本批成功但尚有后续批次；失败通过异常保留重试责任。 */
  ProjectionResult project(BusinessOutboxTask task);
}
