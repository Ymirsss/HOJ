package top.hcode.hoj.service.impl;

import cn.hutool.core.map.MapUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;
import top.hcode.hoj.common.CommonResult;
import top.hcode.hoj.dao.JudgeServerMapper;
import top.hcode.hoj.judge.SandboxRun;
import top.hcode.hoj.pojo.entity.judge.JudgeServer;
import top.hcode.hoj.service.JudgeServerService;

import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;

/**
 * @Author: Himit_ZH
 * @Date: 2021/4/15 11:27
 * @Description:
 */
@Service
@Slf4j(topic = "hoj")
@RefreshScope
public class JudgeServerServiceImpl extends ServiceImpl<JudgeServerMapper, JudgeServer> implements JudgeServerService {


    @Value("${hoj-judge-server.max-task-num}")
    private Integer maxTaskNum;

    @Value("${hoj-judge-server.remote-judge.open}")
    private Boolean isOpenRemoteJudge;

    @Value("${hoj-judge-server.remote-judge.max-task-num}")
    private Integer RemoteJudgeMaxTaskNum;

    @Value("${hoj-judge-server.name}")
    private String name;

    @Override
    public HashMap<String, Object> getJudgeServerInfo() {

        HashMap<String, Object> res = new HashMap<>();

        res.put("version", "20220129");
        res.put("currentTime", new Date());
        res.put("judgeServerName", name);
        res.put("cpu", Runtime.getRuntime().availableProcessors());
        res.put("languages", Arrays.asList("G++ 7.5.0", "GCC 7.5.0", "Python 3.6.9",
                "Python 2.7.17", "OpenJDK 1.8", "Golang 1.16", "C# Mono 4.6.2"));

        if (maxTaskNum == -1) {
            res.put("maxTaskNum", Runtime.getRuntime().availableProcessors() + 1);
        } else {
            res.put("maxTaskNum", maxTaskNum);
        }
        if (isOpenRemoteJudge) {
            res.put("isOpenRemoteJudge", true);
            if (RemoteJudgeMaxTaskNum == -1) {
                res.put("remoteJudgeMaxTaskNum", Runtime.getRuntime().availableProcessors() * 2 + 1);
            } else {
                res.put("remoteJudgeMaxTaskNum", RemoteJudgeMaxTaskNum);
            }
        }

        String versionResp = "";

        try {
            versionResp = SandboxRun.getRestTemplate().getForObject(SandboxRun.getSandboxBaseUrl() + "/version", String.class);
        } catch (Exception e) {
            res.put("SandBoxMsg", MapUtil.builder().put("error", e.getMessage()).map());
            return res;
        }

        res.put("SandBoxMsg", JSONUtil.parseObj(versionResp));
        return res;
    }
}