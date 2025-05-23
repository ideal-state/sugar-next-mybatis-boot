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

import lombok.Data;
import lombok.NonNull;
import org.apache.ibatis.session.SqlSession;
import team.idealstate.sugar.next.database.DatabaseSession;
import team.idealstate.sugar.validate.annotation.NotNull;

@Data
final class MyBatisSession implements DatabaseSession {

    @NonNull
    private final SqlSession sqlSession;

    @NotNull
    @Override
    public <T> T getRepository(@NotNull Class<T> repositoryType) {
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
