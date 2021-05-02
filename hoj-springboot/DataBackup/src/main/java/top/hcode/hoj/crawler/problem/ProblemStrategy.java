package top.hcode.hoj.crawler.problem;

import lombok.Data;
import lombok.experimental.Accessors;
import top.hcode.hoj.pojo.entity.Problem;
import top.hcode.hoj.pojo.entity.Tag;

import java.util.List;

public abstract class ProblemStrategy {

    public abstract RemoteProblemInfo getProblemInfo(String problemId,String author) throws Exception;

    @Data
    @Accessors(chain = true)
    public static
    class RemoteProblemInfo {
        private Problem problem;
        private List<Tag> tagList;
    }
}