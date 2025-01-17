package top.hcode.hoj.service.judge.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import top.hcode.hoj.dao.JudgeServerMapper;

import top.hcode.hoj.pojo.entity.judge.JudgeServer;
import top.hcode.hoj.service.judge.JudgeServerService;

/**
 * @Author: Himit_ZH
 * @Date: 2021/4/15 11:27
 * @Description:
 */
@Service
public class JudgeServerServiceImpl extends ServiceImpl<JudgeServerMapper, JudgeServer> implements JudgeServerService {

}