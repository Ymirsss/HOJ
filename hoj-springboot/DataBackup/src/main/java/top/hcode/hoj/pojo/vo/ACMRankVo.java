package top.hcode.hoj.pojo.vo;

import com.baomidou.mybatisplus.annotation.TableField;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import org.checkerframework.checker.index.qual.LengthOf;
import org.checkerframework.checker.index.qual.SubstringIndexFor;

import java.io.Serializable;

/**
 * @Author: Himit_ZH
 * @Date: 2020/10/27 21:09
 * @Description:
 */
@ApiModel(value="ACM排行榜数据类ACMRankVo", description="")
@Data
public class ACMRankVo implements Serializable {

    @ApiModelProperty(value = "用户id")
    private String uid;

    @ApiModelProperty(value = "用户名")
    private String username;

    @ApiModelProperty(value = "昵称")
    private String nickname;

    @ApiModelProperty(value = "个性签名")
    private String signature;

    @ApiModelProperty(value = "头像地址")
    private String avatar;

    @ApiModelProperty(value = "总提交数")
    private Integer total;

    @ApiModelProperty(value = "总通过数")
    private Integer ac;

    @ApiModelProperty(value = "cf得分")
    private Integer rating;
}