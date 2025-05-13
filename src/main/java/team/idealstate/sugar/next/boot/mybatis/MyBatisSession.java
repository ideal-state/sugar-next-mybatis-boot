/*
 *    Copyright 2025 ideal-state
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package team.idealstate.sugar.next.boot.mybatis;

import java.util.Map;
import lombok.Data;
import lombok.NonNull;
import org.apache.ibatis.binding.MapperRegistry;
import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import team.idealstate.sugar.logging.Log;
import team.idealstate.sugar.next.boot.mybatis.spi.CacheFactory;
import team.idealstate.sugar.next.database.DatabaseSession;
import team.idealstate.sugar.validate.Validation;
import team.idealstate.sugar.validate.annotation.NotNull;

@Data
final class MyBatisSession implements DatabaseSession {

    @NonNull
    private final SqlSession sqlSession;

    @NonNull
    private final ClassLoader classLoader;

    private final CacheFactory cacheFactory;
    private final Integer expired;
    private final Map<String, Object> cacheProperties;

    @NotNull
    @Override
    public <T> T getRepository(@NotNull Class<T> repositoryType) {
        Configuration configuration = sqlSession.getConfiguration();
        MapperRegistry mapperRegistry = configuration.getMapperRegistry();
        if (!mapperRegistry.hasMapper(repositoryType)) {
            Thread thread = Thread.currentThread();
            ClassLoader threadContextClassLoader = thread.getContextClassLoader();
            try {
                thread.setContextClassLoader(getClassLoader());
                String namespace = repositoryType.getName();
                if (cacheFactory != null && !configuration.hasCache(namespace)) {
                    Cache cache = cacheFactory.createCache(namespace, expired, cacheProperties);
                    Validation.notNull(cache, "Cache must not be null.");
                    Log.debug(() -> String.format("Adding cache: %s", namespace));
                    configuration.addCache(cache);
                }
                Log.debug(() -> String.format("Adding mapper: %s", repositoryType.getName()));
                mapperRegistry.addMapper(repositoryType);
            } finally {
                thread.setContextClassLoader(threadContextClassLoader);
            }
        }
        return sqlSession.getMapper(repositoryType);
    }

    @Override
    public void commit() {
        sqlSession.commit();
    }

    @Override
    public void rollback() {
        sqlSession.rollback();
    }

    @Override
    public void close() {
        sqlSession.close();
    }
}
