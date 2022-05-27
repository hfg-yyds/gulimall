package com.hacker.service.impl;

import com.hacker.common.annotation.SystemLog;
import com.hacker.common.exception.AccessReason;
import com.hacker.consts.TaskConstance;
import com.hacker.domain.request.ProcessRequest;
import com.hacker.domain.request.QueryTaskRequest;
import com.hacker.domain.request.TaskRequest;
import com.hacker.service.ProcessInstanceService;
import com.hacker.service.ProcessTaskService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.history.HistoricActivityInstance;
import org.camunda.bpm.engine.rest.dto.runtime.ProcessInstanceDto;
import org.camunda.bpm.engine.rest.dto.task.TaskDto;
import org.camunda.bpm.engine.runtime.ActivityInstance;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.util.List;
import java.util.Map;

/**
 * @Author: Zero
 * @Date: 2022/5/24 09:37
 * @Description:
 */
@Service
@Slf4j
public class ProcessInstanceServiceImpl implements ProcessInstanceService {
    @Autowired
    private ProcessTaskService processTaskService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private HistoryService historyService;

    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    @SystemLog
    public ProcessInstanceDto startProcessInstanceByKey(ProcessRequest request) {
        ProcessInstance processInstance = null;
        Map<String, Object> variables = request.getVariables();
        variables.put("stater", request.getStater());
        //通过流程id或者流程key发起流程
        if (StringUtils.isNotBlank(request.getProcessDefId())) {
            processInstance = runtimeService.startProcessInstanceById(request.getProcessDefId(),
                    request.getBusinessKey(), variables);
        } else if (StringUtils.isNotBlank(request.getProcessDefKey())) {
            processInstance = runtimeService.startProcessInstanceByKey(request.getProcessDefKey(),
                    request.getBusinessKey(), variables);
        }
        Assert.isTrue(processInstance != null, "流程启动失败");
        log.info(String.format("流程启动成功,流程实列Id [{%s}]", processInstance.getProcessInstanceId()));
        return ProcessInstanceDto.fromProcessInstance(processInstance);
    }

    @Override
    public void cancelProcess(TaskRequest request) {
        ActivityInstance tree = runtimeService.getActivityInstance(request.getProcessInstId());
        taskService.createComment(request.getTaskId(), request.getProcessInstId(), "撤回流程");
        if (tree == null) {
            throw AccessReason.PARAM_CHECK_EXCEPTION.exception("活动实例不能为空");
        }
        runtimeService
                .createProcessInstanceModification(request.getProcessInstId())
                .cancelActivityInstance(getInstanceIdForActivity(tree, tree.getActivityId()))
                .startBeforeActivity(request.getTaskDefKey())
                .execute();
    }

    @Override
    public List<TaskDto> rollbackProcess(TaskRequest request) {
        String rejectType = request.getRejectType();
        if (StringUtils.isBlank(rejectType)) {
            throw AccessReason.POCESS_REJECT_TYPE.exception("驳回类型不能为空");
        }
        ActivityInstance tree = runtimeService.getActivityInstance(request.getProcessInstId());
        if (rejectType.equals(TaskConstance.REJECT_TO_START)) {
            List<HistoricActivityInstance> resultList = historyService
                    .createHistoricActivityInstanceQuery()
                    .processInstanceId(request.getProcessInstId())
                    .activityType("userTask")
                    .finished()
                    .orderByHistoricActivityInstanceEndTime()
                    .asc()
                    .list();
            if (resultList == null || resultList.size() <= 0) {
                throw AccessReason.POCESS_REJECT_TYPE.exception("未找到发起节点");
            }
            //找到第一个人工起草节点
            request.setToActId(resultList.get(0).getActivityId());

        } else if (rejectType.equals(TaskConstance.REJECT_TO_LAST)) {
            List<HistoricActivityInstance> resultList = historyService
                    .createHistoricActivityInstanceQuery()
                    .processInstanceId(request.getProcessInstId())
                    .activityType("userTask")
                    .finished()
                    .orderByHistoricActivityInstanceEndTime()
                    .desc()
                    .list();
            if (resultList == null || resultList.size() <= 0) {
                throw AccessReason.POCESS_REJECT_TYPE.exception("未找到上一节点");
            }
            //找到上一个节点
            request.setToActId(resultList.get(0).getActivityId());

        } else if (rejectType.equals(TaskConstance.REJECT_TO_TARGET)) {
            if (StringUtils.isBlank(request.getToActId())) {
                throw AccessReason.POCESS_REJECT_TYPE.exception("指定目标节点不能为空");
            }
        } else {
            throw AccessReason.POCESS_REJECT_TYPE.exception("驳回类型值不对，三种类型  1：起草节点，2：上一节点，3：目标节点");
        }

        taskService.createComment(request.getTaskId(), request.getProcessInstId(), "驳回流程");

        runtimeService
                .createProcessInstanceModification(request.getProcessInstId())
                .cancelActivityInstance(getInstanceIdForActivity(tree, request.getTaskDefKey()))
                .startBeforeActivity(request.getToActId())
                .execute();

        return processTaskService.queryActiveTask(QueryTaskRequest.builder().processInsId(request.getProcessInstId()).build());
    }

    public String getInstanceIdForActivity(ActivityInstance activityInstance, String activityId) {
        ActivityInstance instance = getChildInstanceForActivity(activityInstance, activityId);
        if (instance != null) {
            return instance.getId();
        }
        return null;
    }

    public ActivityInstance getChildInstanceForActivity(ActivityInstance activityInstance, String activityId) {
        if (activityId.equals(activityInstance.getActivityId())) {
            return activityInstance;
        }
        for (ActivityInstance childInstance : activityInstance.getChildActivityInstances()) {
            ActivityInstance instance = getChildInstanceForActivity(childInstance, activityId);
            if (instance != null) {
                return instance;
            }
        }
        return null;
    }

}
