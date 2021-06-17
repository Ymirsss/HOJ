package top.hcode.hoj.judge;


import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import top.hcode.hoj.common.result.CommonResult;
import top.hcode.hoj.pojo.entity.*;
import top.hcode.hoj.service.impl.JudgeServerServiceImpl;
import top.hcode.hoj.service.impl.JudgeServiceImpl;
import top.hcode.hoj.service.impl.RemoteJudgeAccountServiceImpl;
import top.hcode.hoj.utils.Constants;


import java.net.SocketTimeoutException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @Author: Himit_ZH
 * @Date: 2021/4/15 17:29
 * @Description:
 */
@Component
@Slf4j(topic = "hoj")
public class Dispatcher {

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private JudgeServerServiceImpl judgeServerService;

    @Autowired
    private JudgeServiceImpl judgeService;

    @Autowired
    private ChooseServer chooseServer;

    @Autowired
    private RemoteJudgeAccountServiceImpl remoteJudgeAccountService;

    public CommonResult dispatcher(String type, String path, Object data) {
        switch (type) {
            case "judge":
                ToJudge judgeData = (ToJudge) data;
                toJudge(path, judgeData, judgeData.getJudge().getSubmitId(), judgeData.getRemoteJudge() != null);
                break;
            case "compile":
                CompileSpj compileSpj = (CompileSpj) data;
                return toCompile(path, compileSpj);
            default:
                throw new NullPointerException("判题机不支持此调用类型");
        }
        return null;
    }


    public void toJudge(String path, ToJudge data, Long submitId, Boolean isRemote) {
        // 尝试30s
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        AtomicInteger count = new AtomicInteger(0);
        Runnable getResultTask = new Runnable() {
            @Override
            public void run() {
                count.getAndIncrement();
                JudgeServer judgeServer = chooseServer.choose(isRemote);
                if (judgeServer != null) { // 获取到判题机资源
                    CommonResult result = null;
                    try {
                        result = restTemplate.postForObject("http://" + judgeServer.getUrl() + path, data, CommonResult.class);
                    } catch (Exception e) {
                        log.error("调用判题服务器[" + judgeServer.getUrl() + "]发送异常-------------->{}", e.getMessage());
                    } finally {
                        checkResult(result, submitId);
                        // 无论成功与否，都要将对应的当前判题机当前判题数减1
                        reduceCurrentTaskNum(judgeServer.getId());
                        scheduler.shutdown();
                    }
                }

                if (count.get() == 300) { // 300次失败则判为提交失败
                    if (isRemote) { // 远程判题需要将账号归为可用
                        UpdateWrapper<RemoteJudgeAccount> remoteJudgeAccountUpdateWrapper = new UpdateWrapper<>();
                        remoteJudgeAccountUpdateWrapper
                                .eq("username", data.getUsername())
                                .eq("password", data.getPassword())
                                .set("status", true);
                        remoteJudgeAccountService.update(remoteJudgeAccountUpdateWrapper);
                    }
                    checkResult(null, submitId);
                    scheduler.shutdown();
                }
            }
        };
        scheduler.scheduleAtFixedRate(getResultTask, 0, 2, TimeUnit.SECONDS);
    }


    public CommonResult toCompile(String path, CompileSpj data) {
        CommonResult result = CommonResult.errorResponse("没有可用的判题服务器，请重新尝试！");
        JudgeServer judgeServer = chooseServer.choose(false);
        if (judgeServer != null) {
            try {
                result = restTemplate.postForObject("http://" + judgeServer.getUrl() + path, data, CommonResult.class);
            } catch (Exception e) {
                log.error("调用判题服务器[" + judgeServer.getUrl() + "]发送异常-------------->{}", e.getMessage());
            } finally {
                // 无论成功与否，都要将对应的当前判题机当前判题数减1
                reduceCurrentTaskNum(judgeServer.getId());
            }
        }
        return result;
    }


    private void checkResult(CommonResult result, Long submitId) {

        Judge judge = new Judge();
        if (result == null) { // 调用失败
            judge.setSubmitId(submitId);
            judge.setStatus(Constants.Judge.STATUS_SUBMITTED_FAILED.getStatus());
            judge.setErrorMessage("Failed to connect the judgeServer. Please resubmit this submission again!");
            judgeService.updateById(judge);
        } else {
            if (result.getStatus().intValue() != CommonResult.STATUS_SUCCESS) { // 如果是结果码不是200 说明调用有错误
                // 判为系统错误
                judge.setStatus(Constants.Judge.STATUS_SYSTEM_ERROR.getStatus())
                        .setErrorMessage(result.getMsg());
                judgeService.updateById(judge);
            }
        }

    }

    public void reduceCurrentTaskNum(Integer id) {
        UpdateWrapper<JudgeServer> judgeServerUpdateWrapper = new UpdateWrapper<>();
        judgeServerUpdateWrapper.setSql("task_number = task_number-1").eq("id", id);
        boolean isOk = judgeServerService.update(judgeServerUpdateWrapper);
        if (!isOk) { // 重试八次
            tryAgainUpdate(judgeServerUpdateWrapper);
        }
    }

    public void tryAgainUpdate(UpdateWrapper<JudgeServer> updateWrapper) {
        boolean retryable;
        int attemptNumber = 0;
        do {
            boolean success = judgeServerService.update(updateWrapper);
            if (success) {
                return;
            } else {
                attemptNumber++;
                retryable = attemptNumber < 8;
                if (attemptNumber == 8) {
                    break;
                }
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        } while (retryable);
    }
}