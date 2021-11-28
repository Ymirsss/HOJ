package top.hcode.hoj.service.contest.impl;

import cn.hutool.core.date.DateUnit;
import cn.hutool.core.date.DateUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.shiro.SecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.annotation.Transactional;
import top.hcode.hoj.common.result.CommonResult;
import top.hcode.hoj.dao.ContestProblemMapper;
import top.hcode.hoj.dao.JudgeMapper;
import top.hcode.hoj.dao.UserInfoMapper;
import top.hcode.hoj.pojo.dto.ToJudgeDto;
import top.hcode.hoj.pojo.entity.contest.Contest;
import top.hcode.hoj.pojo.entity.contest.ContestProblem;
import top.hcode.hoj.pojo.entity.judge.Judge;
import top.hcode.hoj.pojo.entity.problem.Problem;
import top.hcode.hoj.pojo.entity.user.UserInfo;
import top.hcode.hoj.pojo.vo.ACMContestRankVo;
import top.hcode.hoj.pojo.entity.contest.ContestRecord;
import top.hcode.hoj.dao.ContestRecordMapper;
import top.hcode.hoj.pojo.vo.ContestRecordVo;
import top.hcode.hoj.pojo.vo.OIContestRankVo;
import top.hcode.hoj.pojo.vo.UserRolesVo;
import top.hcode.hoj.service.contest.ContestRecordService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import top.hcode.hoj.service.judge.impl.JudgeServiceImpl;
import top.hcode.hoj.service.problem.impl.ProblemServiceImpl;
import top.hcode.hoj.utils.Constants;
import top.hcode.hoj.utils.RedisUtils;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author Himit_ZH
 * @since 2020-10-23
 */
@Service
public class ContestRecordServiceImpl extends ServiceImpl<ContestRecordMapper, ContestRecord> implements ContestRecordService {


    @Autowired
    private ContestRecordMapper contestRecordMapper;

    @Autowired
    private UserInfoMapper userInfoMapper;

    @Autowired
    private RedisUtils redisUtils;

    @Resource
    private ContestServiceImpl contestService;

    @Resource
    private ContestProblemMapper contestProblemMapper;

    @Resource
    private ProblemServiceImpl problemService;

    @Resource
    private JudgeMapper judgeMapper;


    @Override
    @Transactional(rollbackFor = Exception.class)
    public CommonResult submitContestProblem(ToJudgeDto judgeDto, UserRolesVo userRolesVo, Judge judge) {
        // 首先判断一下比赛的状态是否是正在进行，结束状态都不能提交，比赛前比赛管理员可以提交
        Contest contest = contestService.getById(judgeDto.getCid());

        if (contest == null) {
            return CommonResult.errorResponse("对不起，该比赛不存在！");
        }

        if (contest.getStatus().intValue() == Constants.Contest.STATUS_ENDED.getCode()) {
            return CommonResult.errorResponse("比赛已结束，不可再提交！");
        }

        // 是否为超级管理员或者该比赛的创建者，则为比赛管理者
        boolean root = SecurityUtils.getSubject().hasRole("root");
        if (!root && !contest.getUid().equals(userRolesVo.getUid())) {
            if (contest.getStatus().intValue() == Constants.Contest.STATUS_SCHEDULED.getCode()) {
                return CommonResult.errorResponse("比赛未开始，不可提交！");
            }
            // 需要检查是否有权限在当前比赛进行提交
            CommonResult checkResult = contestService.checkJudgeAuth(contest, userRolesVo.getUid());
            if (checkResult != null) {
                return checkResult;
            }

            /**
             *  需要校验当前比赛是否为保护比赛，同时是否开启账号规则限制，如果有，需要对当前用户的用户名进行验证
             */

            if (contest.getAuth().equals(Constants.Contest.AUTH_PROTECT.getCode())
                    && contest.getOpenAccountLimit()
                    && !contestService.checkAccountRule(contest.getAccountLimitRule(), userRolesVo.getUsername())) {
                return CommonResult.errorResponse("对不起！本次比赛只允许特定账号规则的用户参赛！", CommonResult.STATUS_ACCESS_DENIED);
            }
        }

        // 查询获取对应的pid和cpid
        QueryWrapper<ContestProblem> contestProblemQueryWrapper = new QueryWrapper<>();
        contestProblemQueryWrapper.eq("cid", judgeDto.getCid()).eq("display_id", judgeDto.getPid());
        ContestProblem contestProblem = contestProblemMapper.selectOne(contestProblemQueryWrapper);
        judge.setCpid(contestProblem.getId())
                .setPid(contestProblem.getPid());

        Problem problem = problemService.getById(contestProblem.getPid());
        if (problem.getAuth() == 2) {
            return CommonResult.errorResponse("错误！当前题目不可提交！", CommonResult.STATUS_FORBIDDEN);
        }
        judge.setDisplayPid(problem.getProblemId());

        // 将新提交数据插入数据库
        judgeMapper.insert(judge);

        // 管理员比赛前的提交不纳入记录
        if (contest.getStatus().intValue() == Constants.Contest.STATUS_RUNNING.getCode()) {
            // 同时初始化写入contest_record表
            ContestRecord contestRecord = new ContestRecord();
            contestRecord.setDisplayId(judgeDto.getPid())
                    .setCpid(contestProblem.getId())
                    .setSubmitId(judge.getSubmitId())
                    .setPid(judge.getPid())
                    .setUsername(userRolesVo.getUsername())
                    .setRealname(userRolesVo.getRealname())
                    .setUid(userRolesVo.getUid())
                    .setCid(judge.getCid())
                    .setSubmitTime(judge.getSubmitTime())
                    // 设置比赛开始时间到提交时间之间的秒数
                    .setTime(DateUtil.between(contest.getStartTime(), judge.getSubmitTime(), DateUnit.SECOND));
            contestRecordMapper.insert(contestRecord);

        }
        return null;
    }

    @Override
    public IPage<ContestRecord> getACInfo(Integer currentPage, Integer limit, Integer status, Long cid, String contestCreatorId) {

        List<ContestRecord> acInfo = contestRecordMapper.getACInfo(status, cid);

        HashMap<Long, String> pidMapUidAndPid = new HashMap<>(12);
        HashMap<String, Long> UidAndPidMapTime = new HashMap<>(12);

        List<UserInfo> superAdminList = getSuperAdminList();
        List<String> superAdminUidList = superAdminList.stream().map(UserInfo::getUuid).collect(Collectors.toList());

        List<ContestRecord> userACInfo = new LinkedList<>();

        for (ContestRecord contestRecord : acInfo) {

            if (contestRecord.getUid().equals(contestCreatorId)
                    || superAdminUidList.contains(contestRecord.getUid())) { // 超级管理员和比赛创建者的提交跳过
                continue;
            }

            contestRecord.setFirstBlood(false);
            String uidAndPid = pidMapUidAndPid.get(contestRecord.getPid());
            if (uidAndPid == null) {
                pidMapUidAndPid.put(contestRecord.getPid(), contestRecord.getUid() + contestRecord.getPid());
                UidAndPidMapTime.put(contestRecord.getUid() + contestRecord.getPid(), contestRecord.getTime());
            } else {
                Long firstTime = UidAndPidMapTime.get(uidAndPid);
                Long tmpTime = contestRecord.getTime();
                if (tmpTime < firstTime) {
                    pidMapUidAndPid.put(contestRecord.getPid(), contestRecord.getUid() + contestRecord.getPid());
                    UidAndPidMapTime.put(contestRecord.getUid() + contestRecord.getPid(), tmpTime);
                }
            }
            userACInfo.add(contestRecord);
        }


        List<ContestRecord> pageList = new ArrayList<>();

        int count = userACInfo.size();

        //计算当前页第一条数据的下标
        int currId = currentPage > 1 ? (currentPage - 1) * limit : 0;
        for (int i = 0; i < limit && i < count - currId; i++) {
            ContestRecord contestRecord = userACInfo.get(currId + i);
            if (pidMapUidAndPid.get(contestRecord.getPid()).equals(contestRecord.getUid() + contestRecord.getPid())) {
                contestRecord.setFirstBlood(true);
            }
            pageList.add(contestRecord);
        }


        Page<ContestRecord> page = new Page<>(currentPage, limit);
        page.setSize(limit);
        page.setCurrent(currentPage);
        page.setTotal(count);
        page.setRecords(pageList);

        return page;
    }

    @Override
    public List<UserInfo> getSuperAdminList() {
        return userInfoMapper.getSuperAdminList();
    }


    @Override
    public IPage<ACMContestRankVo> getContestACMRank(Boolean isOpenSealRank, Contest contest,
                                                     int currentPage, int limit) {

        List<ContestRecordVo> acmContestRecord = getACMContestRecord(contest.getAuthor(), contest.getId());

        // 进行排序计算
        List<ACMContestRankVo> orderResultList = calcACMRank(isOpenSealRank, contest, acmContestRecord);
        // 计算好排行榜，然后进行分页
        Page<ACMContestRankVo> page = new Page<>(currentPage, limit);
        int count = orderResultList.size();
        List<ACMContestRankVo> pageList = new ArrayList<>();
        //计算当前页第一条数据的下标
        int currId = currentPage > 1 ? (currentPage - 1) * limit : 0;
        for (int i = 0; i < limit && i < count - currId; i++) {
            pageList.add(orderResultList.get(currId + i));
        }
        page.setSize(limit);
        page.setCurrent(currentPage);
        page.setTotal(count);
        page.setRecords(pageList);

        return page;
    }


    @Override
    public IPage<OIContestRankVo> getContestOIRank(Long cid, String contestAuthor, Boolean isOpenSealRank, Date sealTime,
                                                   Date startTime, Date endTime, int currentPage, int limit) {

        // 获取每个用户每道题最近一次提交
        if (!isOpenSealRank) {
            // 超级管理员和比赛管理员选择强制刷新 或者 比赛结束
            return getOIContestRank(cid, contestAuthor, false, sealTime, startTime, endTime, currentPage, limit);
        } else {
            String key = Constants.Contest.OI_CONTEST_RANK_CACHE.getName() + "_" + cid;
            Page<OIContestRankVo> page = (Page<OIContestRankVo>) redisUtils.get(key);
            if (page == null) {
                page = getOIContestRank(cid, contestAuthor, true, sealTime, startTime, endTime, currentPage, limit);
                redisUtils.set(key, page, 2 * 3600);
            }
            return page;
        }

    }


    public Page<OIContestRankVo> getOIContestRank(Long cid, String contestAuthor, Boolean isOpenSealRank, Date sealTime,
                                                  Date startTime, Date endTime, int currentPage, int limit) {

        List<ContestRecordVo> oiContestRecord = contestRecordMapper.getOIContestRecord(cid, contestAuthor, isOpenSealRank, sealTime, startTime, endTime);
        // 计算排名
        List<OIContestRankVo> orderResultList = calcOIRank(oiContestRecord);

        // 计算好排行榜，然后进行分页
        Page<OIContestRankVo> page = new Page<>(currentPage, limit);
        int count = orderResultList.size();
        List<OIContestRankVo> pageList = new ArrayList<>();
        //计算当前页第一条数据的下标
        int currId = currentPage > 1 ? (currentPage - 1) * limit : 0;
        for (int i = 0; i < limit && i < count - currId; i++) {
            pageList.add(orderResultList.get(currId + i));
        }
        page.setSize(limit);
        page.setCurrent(currentPage);
        page.setTotal(count);
        page.setRecords(pageList);
        return page;
    }

    @Override
    public List<ContestRecordVo> getOIContestRecord(Long cid, String contestAuthor, Boolean isOpenSealRank, Date sealTime, Date startTime, Date endTime) {
        return contestRecordMapper.getOIContestRecord(cid, contestAuthor, isOpenSealRank, sealTime, startTime, endTime);
    }

    @Override
    public List<ContestRecordVo> getACMContestRecord(String username, Long cid) {
        return contestRecordMapper.getACMContestRecord(username, cid);
    }


    public List<ACMContestRankVo> calcACMRank(boolean isOpenSealRank, Contest contest, List<ContestRecordVo> contestRecordList) {

        List<UserInfo> superAdminList = getSuperAdminList();

        List<String> superAdminUidList = superAdminList.stream().map(UserInfo::getUuid).collect(Collectors.toList());

        List<ACMContestRankVo> result = new ArrayList<>();

        HashMap<String, Integer> uidMapIndex = new HashMap<>();

        int index = 0;

        HashMap<String, Long> firstACMap = new HashMap<>();

        for (ContestRecordVo contestRecord : contestRecordList) {

            if (superAdminUidList.contains(contestRecord.getUid())) { // 超级管理员的提交不入排行榜
                continue;
            }

            ACMContestRankVo ACMContestRankVo;
            if (!uidMapIndex.containsKey(contestRecord.getUid())) { // 如果该用户信息没还记录

                // 初始化参数
                ACMContestRankVo = new ACMContestRankVo();
                ACMContestRankVo.setRealname(contestRecord.getRealname())
                        .setAvatar(contestRecord.getAvatar())
                        .setSchool(contestRecord.getSchool())
                        .setGender(contestRecord.getGender())
                        .setUid(contestRecord.getUid())
                        .setUsername(contestRecord.getUsername())
                        .setNickname(contestRecord.getNickname())
                        .setAc(0)
                        .setTotalTime(0L)
                        .setTotal(0);

                HashMap<String, HashMap<String, Object>> submissionInfo = new HashMap<>();
                ACMContestRankVo.setSubmissionInfo(submissionInfo);

                result.add(ACMContestRankVo);
                uidMapIndex.put(contestRecord.getUid(), index);
                index++;
            } else {
                ACMContestRankVo = result.get(uidMapIndex.get(contestRecord.getUid())); // 根据记录的index进行获取
            }

            HashMap<String, Object> problemSubmissionInfo = ACMContestRankVo.getSubmissionInfo().getOrDefault(contestRecord.getDisplayId(), new HashMap<>());

            ACMContestRankVo.setTotal(ACMContestRankVo.getTotal() + 1);

            // 如果是当前是开启封榜的时段和同时该提交是处于封榜时段 尝试次数+1
            if (isOpenSealRank && isInSealTimeSubmission(contest, contestRecord.getSubmitTime())) {

                int tryNum = (int) problemSubmissionInfo.getOrDefault("tryNum", 0);
                problemSubmissionInfo.put("tryNum", tryNum + 1);

            } else {

                // 如果该题目已经AC过了，其它都不记录了
                if ((Boolean) problemSubmissionInfo.getOrDefault("isAC", false)) {
                    continue;
                }

                // 记录已经按题目提交耗时time升序了

                // 通过的话
                if (contestRecord.getStatus().intValue() == Constants.Contest.RECORD_AC.getCode()) {
                    // 总解决题目次数ac+1
                    ACMContestRankVo.setAc(ACMContestRankVo.getAc() + 1);

                    // 判断是不是first AC
                    boolean isFirstAC = false;
                    Long time = firstACMap.getOrDefault(contestRecord.getDisplayId(), null);
                    if (time == null) {
                        isFirstAC = true;
                        firstACMap.put(contestRecord.getDisplayId(), contestRecord.getTime());
                    } else {
                        // 相同提交时间也是first AC
                        if (time.longValue() == contestRecord.getTime().longValue()) {
                            isFirstAC = true;
                        }
                    }

                    int errorNumber = (int) problemSubmissionInfo.getOrDefault("errorNum", 0);
                    problemSubmissionInfo.put("isAC", true);
                    problemSubmissionInfo.put("isFirstAC", isFirstAC);
                    problemSubmissionInfo.put("ACTime", contestRecord.getTime());
                    problemSubmissionInfo.put("errorNum", errorNumber);

                    // 同时计算总耗时，总耗时加上 该题目未AC前的错误次数*20*60+题目AC耗时
                    ACMContestRankVo.setTotalTime(ACMContestRankVo.getTotalTime() + errorNumber * 20 * 60 + contestRecord.getTime());

                    // 未通过同时需要记录罚时次数
                } else if (contestRecord.getStatus().intValue() == Constants.Contest.RECORD_NOT_AC_PENALTY.getCode()) {

                    int errorNumber = (int) problemSubmissionInfo.getOrDefault("errorNum", 0);
                    problemSubmissionInfo.put("errorNum", errorNumber + 1);
                } else {

                    int errorNumber = (int) problemSubmissionInfo.getOrDefault("errorNum", 0);
                    problemSubmissionInfo.put("errorNum", errorNumber);
                }
            }
            ACMContestRankVo.getSubmissionInfo().put(contestRecord.getDisplayId(), problemSubmissionInfo);
        }

        List<ACMContestRankVo> orderResultList = result.stream().sorted(Comparator.comparing(ACMContestRankVo::getAc, Comparator.reverseOrder()) // 先以总ac数降序
                .thenComparing(ACMContestRankVo::getTotalTime) //再以总耗时升序
        ).collect(Collectors.toList());

        return orderResultList;
    }

    public List<OIContestRankVo> calcOIRank(List<ContestRecordVo> oiContestRecord) {

        List<UserInfo> superAdminList = getSuperAdminList();

        List<String> superAdminUidList = superAdminList.stream().map(UserInfo::getUuid).collect(Collectors.toList());

        List<OIContestRankVo> result = new ArrayList<>();

        HashMap<String, Integer> uidMapIndex = new HashMap<>();

        HashMap<String, HashMap<String, Integer>> uidMapTime = new HashMap<>();

        int index = 0;
        for (ContestRecordVo contestRecord : oiContestRecord) {

            if (superAdminUidList.contains(contestRecord.getUid())) { // 超级管理员的提交不入排行榜
                continue;
            }

            if (contestRecord.getStatus() == 1) { // AC
                HashMap<String, Integer> pidMapTime = uidMapTime.get(contestRecord.getUid());
                if (pidMapTime != null) {
                    Integer useTime = pidMapTime.get(contestRecord.getDisplayId());
                    if (useTime != null) {
                        if (useTime > contestRecord.getUseTime()) {  // 如果时间消耗比原来的少
                            pidMapTime.put(contestRecord.getDisplayId(), contestRecord.getUseTime());
                        }
                    } else {
                        pidMapTime.put(contestRecord.getDisplayId(), contestRecord.getUseTime());
                    }
                } else {
                    HashMap<String, Integer> tmp = new HashMap<>();
                    tmp.put(contestRecord.getDisplayId(), contestRecord.getUseTime());
                    uidMapTime.put(contestRecord.getUid(), tmp);
                }
            }

            OIContestRankVo oiContestRankVo;
            if (!uidMapIndex.containsKey(contestRecord.getUid())) { // 如果该用户信息没还记录
                // 初始化参数
                oiContestRankVo = new OIContestRankVo();
                oiContestRankVo.setRealname(contestRecord.getRealname())
                        .setUid(contestRecord.getUid())
                        .setUsername(contestRecord.getUsername())
                        .setSchool(contestRecord.getSchool())
                        .setAvatar(contestRecord.getAvatar())
                        .setGender(contestRecord.getGender())
                        .setNickname(contestRecord.getNickname())
                        .setTotalScore(0);


                HashMap<String, Integer> submissionInfo = new HashMap<>();
                oiContestRankVo.setSubmissionInfo(submissionInfo);

                result.add(oiContestRankVo);
                uidMapIndex.put(contestRecord.getUid(), index);
                index++;
            } else {
                oiContestRankVo = result.get(uidMapIndex.get(contestRecord.getUid())); // 根据记录的index进行获取
            }

            // 记录总分
            HashMap<String, Integer> submissionInfo = oiContestRankVo.getSubmissionInfo();
            Integer score = submissionInfo.get(contestRecord.getDisplayId());

            if (contestRecord.getScore() != null) {
                if (score != null) { // 为了避免同个提交时间的重复计算
                    oiContestRankVo.setTotalScore(oiContestRankVo.getTotalScore() - score + contestRecord.getScore());
                } else {
                    oiContestRankVo.setTotalScore(oiContestRankVo.getTotalScore() + contestRecord.getScore());
                }
            }
            submissionInfo.put(contestRecord.getDisplayId(), contestRecord.getScore());

        }


        for (OIContestRankVo oiContestRankVo : result) {
            HashMap<String, Integer> pidMapTime = uidMapTime.get(oiContestRankVo.getUid());
            int sumTime = 0;
            if (pidMapTime != null) {
                for (String key : pidMapTime.keySet()) {
                    Integer time = pidMapTime.get(key);
                    sumTime += time == null ? 0 : time;
                }
            }
            oiContestRankVo.setTotalTime(sumTime);
            oiContestRankVo.setTimeInfo(pidMapTime);
        }

        // 根据总得分进行降序,再根据总时耗升序排序
        List<OIContestRankVo> orderResultList = result.stream()
                .sorted(Comparator.comparing(OIContestRankVo::getTotalScore, Comparator.reverseOrder())
                        .thenComparing(OIContestRankVo::getTotalTime, Comparator.naturalOrder()))
                .collect(Collectors.toList());
        return orderResultList;
    }


    private boolean isInSealTimeSubmission(Contest contest, Date submissionDate) {
        return DateUtil.isIn(submissionDate, contest.getSealRankTime(), contest.getEndTime());
    }
}