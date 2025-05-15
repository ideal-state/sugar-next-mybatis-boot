package team.idealstate.sugar.next.boot.mybatis.spi;

import org.apache.ibatis.session.Configuration;
import team.idealstate.sugar.validate.annotation.NotNull;

public interface MyBatisConfigurationBuilder {

    void build(@NotNull Configuration configuration);
}
