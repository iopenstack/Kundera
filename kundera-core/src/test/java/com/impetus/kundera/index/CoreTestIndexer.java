/*******************************************************************************
 * * Copyright 2013 Impetus Infotech.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *      http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 ******************************************************************************/
package com.impetus.kundera.index;

import java.util.Map;

/**
 * @author amresh.singh
 *
 */
public class CoreTestIndexer implements Indexer
{

    @Override
    public void index(Class entityClazz, Map<String, Object> values)
    {
    }

    @Override
    public Map<String, Object> search(String queryString, int start, int count)
    {

        return null;
    }

    @Override
    public Map<String, Object> search(Class<?> parentClass, Class<?> childClass, Object entityId, int start, int count)
    {
        
        return null;
    }

    @Override
    public void unIndex(Class entityClazz, Object entity)
    {
    }

    @Override
    public void close()
    {
    }

}
