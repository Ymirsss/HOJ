package top.hcode.hoj.service.impl;

import top.hcode.hoj.pojo.entity.Tag;
import top.hcode.hoj.dao.TagMapper;
import top.hcode.hoj.service.TagService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author Himit_ZH
 * @since 2020-10-23
 */
@Service
public class TagServiceImpl extends ServiceImpl<TagMapper, Tag> implements TagService {

}
