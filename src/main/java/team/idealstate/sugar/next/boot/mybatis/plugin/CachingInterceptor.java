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

package team.idealstate.sugar.next.boot.mybatis.plugin;

import java.lang.reflect.Field;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.CachingExecutor;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import team.idealstate.sugar.next.boot.mybatis.exception.MyBatisException;

public class CachingInterceptor implements Interceptor {
    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        throw new MyBatisException(new UnsupportedOperationException());
    }

    @Override
    public Object plugin(Object target) {
        if (!(target instanceof Executor)) {
            return target;
        }
        return new CachingExecutorWrapper((Executor) target);
    }

    public static class CachingExecutorWrapper extends CachingExecutor {

        public CachingExecutorWrapper(Executor delegate) {
            super(delegate);
        }

        protected MappedStatement processMappedStatement(MappedStatement ms) {
            if (!ms.isUseCache()) {
                return ms;
            }
            Configuration configuration = ms.getConfiguration();
            String namespace = ms.getId();
            Cache cache = configuration.getCache(namespace);
            if (Objects.equals(cache, ms.getCache())) {
                return ms;
            }
            try {
                Field field = ms.getClass().getDeclaredField("cache");
                field.setAccessible(true);
                field.set(ms, cache);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new MyBatisException(e);
            }
            return ms;
        }

        @Override
        public int update(MappedStatement ms, Object parameterObject) throws SQLException {
            return super.update(processMappedStatement(ms), parameterObject);
        }

        @Override
        public <E> Cursor<E> queryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds)
                throws SQLException {
            return super.queryCursor(processMappedStatement(ms), parameter, rowBounds);
        }

        @Override
        public <E> List<E> query(
                MappedStatement ms, Object parameterObject, RowBounds rowBounds, ResultHandler resultHandler)
                throws SQLException {
            return super.query(processMappedStatement(ms), parameterObject, rowBounds, resultHandler);
        }

        @Override
        public <E> List<E> query(
                MappedStatement ms,
                Object parameterObject,
                RowBounds rowBounds,
                ResultHandler resultHandler,
                CacheKey key,
                BoundSql boundSql)
                throws SQLException {
            return super.query(processMappedStatement(ms), parameterObject, rowBounds, resultHandler, key, boundSql);
        }

        @Override
        public CacheKey createCacheKey(
                MappedStatement ms, Object parameterObject, RowBounds rowBounds, BoundSql boundSql) {
            return super.createCacheKey(processMappedStatement(ms), parameterObject, rowBounds, boundSql);
        }

        @Override
        public boolean isCached(MappedStatement ms, CacheKey key) {
            return super.isCached(processMappedStatement(ms), key);
        }

        @Override
        public void deferLoad(
                MappedStatement ms, MetaObject resultObject, String property, CacheKey key, Class<?> targetType) {
            super.deferLoad(processMappedStatement(ms), resultObject, property, key, targetType);
        }
    }
}
