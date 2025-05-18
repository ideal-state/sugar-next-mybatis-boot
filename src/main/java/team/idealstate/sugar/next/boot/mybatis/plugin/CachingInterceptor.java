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
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.BatchResult;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;
import team.idealstate.sugar.logging.Log;
import team.idealstate.sugar.next.boot.mybatis.exception.MyBatisException;
import team.idealstate.sugar.validate.Validation;
import team.idealstate.sugar.validate.annotation.NotNull;
import team.idealstate.sugar.validate.annotation.Nullable;

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

    public static class CachingExecutorWrapper implements Executor {

        private final Executor delegate;
        private final Deque<CachePlan> plans = new ConcurrentLinkedDeque<>();

        public CachingExecutorWrapper(@NotNull Executor delegate) {
            Validation.notNull(delegate, "Delegate must not be null.");
            this.delegate = delegate;
            delegate.setExecutorWrapper(this);
        }

        protected final String getNamespace(MappedStatement ms) {
            String id = ms.getId();
            return id.substring(0, id.lastIndexOf('.'));
        }

        protected final MappedStatement preprocess(MappedStatement ms) {
            clearLocalCache();
            String namespace = getNamespace(ms);
            Cache cache = ms.getConfiguration().getCache(namespace);
            Cache oldCache = ms.getCache();
            if (!Objects.equals(cache, oldCache)) {
                try {
                    Field field = ms.getClass().getDeclaredField("cache");
                    field.setAccessible(true);
                    field.set(ms, cache);
                    if (oldCache != null) {
                        oldCache.clear();
                    }
                } catch (NoSuchFieldException | IllegalAccessException e) {
                    throw new MyBatisException(e);
                }
            }
            return ms;
        }

        protected final void ensureNoOutParams(MappedStatement ms, BoundSql boundSql) {
            preprocess(ms);
            if (ms.getStatementType() == StatementType.CALLABLE) {
                for (ParameterMapping parameterMapping : boundSql.getParameterMappings()) {
                    if (parameterMapping.getMode() != ParameterMode.IN) {
                        throw new ExecutorException(
                                "Caching stored procedures with OUT params is not supported.  Please configure useCache=false in "
                                        + ms.getId() + " statement.");
                    }
                }
            }
        }

        @NotNull
        protected final CachePlan makePlan(
                @NotNull MappedStatement ms, @NotNull Cache cache, @NotNull CacheKey key, Object value) {
            Validation.notNull(ms, "Mapped statement must not be null.");
            Validation.notNull(cache, "Cache must not be null.");
            Validation.notNull(key, "Cache key must not be null.");
            return new CachePlan(getNamespace(ms), ms.getId(), cache, key, value, System.nanoTime(), true, null);
        }

        @Nullable
        protected final CachePlan pushPlan(@NotNull CachePlan cachePlan) {
            Validation.notNull(cachePlan, "Cache plan must not be null.");
            String namespace = cachePlan.getNamespace();
            String key = String.valueOf(cachePlan.getKey());
            CachePlan last = null;
            for (CachePlan plan : plans) {
                if (!cachePlan.isValid()) {
                    if (namespace.equals(plan.getNamespace())) {
                        if (plan.isValid()) {
                            plan.drop();
                        }
                        if (last == null) {
                            last = plan;
                        }
                    }
                } else if (namespace.equals(plan.getNamespace())
                        && Objects.equals(key, String.valueOf(plan.getKey()))) {
                    if (plan.isValid()) {
                        plan.drop();
                    }
                    if (last == null) {
                        last = plan;
                    }
                    break;
                }
            }
            plans.push(cachePlan);
            return last;
        }

        @Nullable
        protected final Object readCache(@NotNull MappedStatement ms, @NotNull Cache cache, @NotNull CacheKey key) {
            Validation.notNull(ms, "Mapped statement must not be null.");
            Validation.notNull(cache, "Cache must not be null.");
            Validation.notNull(key, "Cache key must not be null.");
            if (!SqlCommandType.SELECT.equals(ms.getSqlCommandType())) {
                return null;
            }
            String namespace = getNamespace(ms);
            String id = ms.getId();
            for (CachePlan plan : plans) {
                if (plan.isValid()
                        && namespace.equals(plan.getNamespace())
                        && id.equals(plan.getId())
                        && key.toString().equals(String.valueOf(plan.getKey()))) {
                    return plan.getValue();
                }
            }
            return cache.getObject(key);
        }

        @SuppressWarnings("UnusedReturnValue")
        @Nullable
        protected final CachePlan writeCache(
                @NotNull MappedStatement ms, @NotNull Cache cache, @NotNull CacheKey key, Object value) {
            Validation.notNull(ms, "Mapped statement must not be null.");
            Validation.notNull(cache, "Cache must not be null.");
            Validation.notNull(key, "Cache key must not be null.");
            if (!SqlCommandType.SELECT.equals(ms.getSqlCommandType())) {
                return null;
            }
            return pushPlan(makePlan(ms, cache, key, value));
        }

        protected final void flushCacheIfRequired(@NotNull MappedStatement ms, @NotNull Cache cache) {
            Validation.notNull(ms, "Mapped statement must not be null.");
            Validation.notNull(ms, "Mapped statement must not be null.");
            Validation.notNull(cache, "Cache must not be null.");
            if (!ms.isFlushCacheRequired()) {
                return;
            }
            pushPlan(new CachePlan(getNamespace(ms), ms.getId(), cache, null, null, System.nanoTime(), false, null)
                    .drop());
        }

        protected void rollbackCache(boolean required) {
            if (plans.isEmpty()) {
                return;
            }
            if (required) {
                Set<String> excludes = new HashSet<>(plans.size());
                Iterator<CachePlan> iterator = plans.iterator();
                while (iterator.hasNext()) {
                    iterator.next().clear(excludes);
                    iterator.remove();
                }
            } else {
                plans.clear();
            }
        }

        @SuppressWarnings("unused")
        protected final void commitCache(boolean required) {
            if (plans.isEmpty()) {
                return;
            }
            Set<String> excludes = new HashSet<>(plans.size());
            Iterator<CachePlan> iterator = plans.descendingIterator();
            while (iterator.hasNext()) {
                iterator.next().apply(excludes);
                iterator.remove();
            }
        }

        @Override
        public int update(MappedStatement ms, Object parameterObject) throws SQLException {
            preprocess(ms);
            flushCacheIfRequired(ms, ms.getCache());
            return delegate.update(ms, parameterObject);
        }

        @Override
        public <E> Cursor<E> queryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds)
                throws SQLException {
            preprocess(ms);
            flushCacheIfRequired(ms, ms.getCache());
            return delegate.queryCursor(ms, parameter, rowBounds);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <E> List<E> query(
                MappedStatement ms,
                Object parameterObject,
                RowBounds rowBounds,
                ResultHandler resultHandler,
                CacheKey key,
                BoundSql boundSql)
                throws SQLException {
            preprocess(ms);
            Cache cache = ms.getCache();
            if (cache != null) {
                flushCacheIfRequired(ms, cache);
                if (ms.isUseCache() && resultHandler == null) {
                    ensureNoOutParams(ms, boundSql);
                    List<E> result = (List<E>) readCache(ms, cache, key);
                    if (result == null) {
                        result = delegate.query(ms, parameterObject, rowBounds, null, key, boundSql);
                        writeCache(ms, cache, key, result);
                    }
                    return result;
                }
            }
            return delegate.query(ms, parameterObject, rowBounds, resultHandler, key, boundSql);
        }

        @Override
        public Transaction getTransaction() {
            return delegate.getTransaction();
        }

        @Override
        public void close(boolean forceRollback) {
            try {
                if (forceRollback) {
                    rollbackCache(true);
                } else {
                    commitCache(true);
                }
            } finally {
                delegate.close(forceRollback);
            }
        }

        @Override
        public boolean isClosed() {
            return delegate.isClosed();
        }

        @Override
        public <E> List<E> query(
                MappedStatement ms, Object parameterObject, RowBounds rowBounds, ResultHandler resultHandler)
                throws SQLException {
            preprocess(ms);
            BoundSql boundSql = ms.getBoundSql(parameterObject);
            CacheKey key = createCacheKey(ms, parameterObject, rowBounds, boundSql);
            return query(ms, parameterObject, rowBounds, resultHandler, key, boundSql);
        }

        @Override
        public List<BatchResult> flushStatements() throws SQLException {
            return delegate.flushStatements();
        }

        @Override
        public void commit(boolean required) throws SQLException {
            delegate.commit(required);
            commitCache(required);
        }

        @Override
        public void rollback(boolean required) throws SQLException {
            try {
                delegate.rollback(required);
            } finally {
                rollbackCache(required);
            }
        }

        @Override
        public CacheKey createCacheKey(
                MappedStatement ms, Object parameterObject, RowBounds rowBounds, BoundSql boundSql) {
            return delegate.createCacheKey(preprocess(ms), parameterObject, rowBounds, boundSql);
        }

        @Override
        public boolean isCached(MappedStatement ms, CacheKey key) {
            return delegate.isCached(preprocess(ms), key);
        }

        @Override
        public void deferLoad(
                MappedStatement ms, MetaObject resultObject, String property, CacheKey key, Class<?> targetType) {
            delegate.deferLoad(preprocess(ms), resultObject, property, key, targetType);
        }

        @Override
        public void clearLocalCache() {
            delegate.clearLocalCache();
        }

        @Override
        public void setExecutorWrapper(Executor executor) {
            throw new UnsupportedOperationException("This method should not be called");
        }

        @Data
        @AllArgsConstructor(access = AccessLevel.PRIVATE)
        protected static final class CachePlan {
            @NonNull
            private final String namespace;

            @NonNull
            private final String id;

            @NonNull
            private final Cache cache;

            private final CacheKey key;

            @Setter(AccessLevel.PRIVATE)
            private volatile Object value;

            private final long timestamp;

            @Setter(AccessLevel.PRIVATE)
            private boolean valid;

            @Getter(AccessLevel.PRIVATE)
            @Setter(AccessLevel.PRIVATE)
            private volatile String uniqueId;

            @NotNull
            private String makeUniqueId() {
                String uniqueId = getUniqueId();
                return uniqueId == null ? (this.uniqueId = getNamespace()) : uniqueId;
            }

            @NotNull
            public CachePlan drop() {
                setValid(false);
                setValue(null);
                return this;
            }

            public void apply(@NotNull Set<String> excludes) {
                if (isValid()) {
                    try {
                        cache.putObject(getKey(), getValue());
                    } catch (Throwable e) {
                        Log.error(e);
                    }
                } else {
                    clear(excludes);
                }
            }

            @NotNull
            public void clear(@NotNull Set<String> excludes) {
                try {
                    if (!Validation.requireNotNull(excludes, "Excludes must not be null.")
                            .add(makeUniqueId())) {
                        return;
                    }
                } finally {
                    drop();
                }
                try {
                    cache.clear();
                } catch (Throwable e) {
                    Log.error(e);
                }
            }
        }
    }
}
