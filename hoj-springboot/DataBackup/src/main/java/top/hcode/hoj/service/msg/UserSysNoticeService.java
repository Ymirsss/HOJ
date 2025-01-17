package top.hcode.hoj.service.msg;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import top.hcode.hoj.pojo.entity.msg.UserSysNotice;
import top.hcode.hoj.pojo.vo.SysMsgVo;

public interface UserSysNoticeService extends IService<UserSysNotice> {
    IPage<SysMsgVo> getSysNotice(int limit, int currentPage, String uid);

    IPage<SysMsgVo> getMineNotice(int limit, int currentPage, String uid);
}