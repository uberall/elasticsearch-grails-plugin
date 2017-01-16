/*
 * Copyright 2002-2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package grails.plugins.elasticsearch.util

import grails.core.GrailsDomainClass
import grails.plugins.elasticsearch.ElasticSearchContextHolder
import grails.plugins.elasticsearch.ElasticSearchService
import grails.plugins.elasticsearch.mapping.SearchableClassMapping
import org.apache.commons.logging.LogFactory
import grails.plugins.elasticsearch.exception.IndexException
import org.elasticsearch.index.query.QueryBuilder

class DomainDynamicMethodsUtils {

    static final LOG = LogFactory.getLog(this)

    /**
     * Injects the dynamic methods in the searchable domain classes.
     * Considers that the mapping has been resolved beforehand.
     *
     * @param grailsApplication
     * @param applicationContext
     * @return
     */
    static injectDynamicMethods(grailsApplication, applicationContext) {
        def elasticSearchService = applicationContext.getBean(ElasticSearchService)
        def elasticSearchContextHolder = applicationContext.getBean(ElasticSearchContextHolder)

        for (GrailsDomainClass domain in grailsApplication.domainClasses) {
            String searchablePropertyName = getSearchablePropertyName(grailsApplication)
            if (!domain.getPropertyValue(searchablePropertyName)) {
                continue
            }

            def domainCopy = domain
            // Only inject the methods if the domain is mapped as "root"
            if (!elasticSearchContextHolder.getMappingContext(domainCopy)?.root) {
                continue
            }
            SearchableClassMapping scm = elasticSearchContextHolder.getMappingContext(domainCopy)
            def indexAndType = [indices: scm.queryingIndex, types: domainCopy.clazz]

			String searchMethodName = grailsApplication.config.elasticSearch.searchMethodName ?: 'search'
			String countHitsMethodName = grailsApplication.config.elasticSearch.countHitsMethodName ?: 'countHits'

            // Inject the search method
            domain.metaClass.'static'."$searchMethodName" << { String q, Map params = [:] ->
                elasticSearchService.search(q, params + indexAndType)
            }
            domain.metaClass.'static'."$searchMethodName" << { Map params = [:], Closure q ->
                elasticSearchService.search(params + indexAndType, q)
            }
            domain.metaClass.'static'."$searchMethodName" << { Closure q, Map params = [:] ->
                elasticSearchService.search(params + indexAndType, q)
            }
            domain.metaClass.'static'."$searchMethodName" << { Closure q, Closure f, Map params = [:] ->
                elasticSearchService.search(q, f, params + indexAndType)
            }
            domain.metaClass.'static'."$searchMethodName" << { Map params, Closure q, Closure f ->
                elasticSearchService.search(params + indexAndType, q, f)
            }
            domain.metaClass.'static'."$searchMethodName" << { Map params, QueryBuilder q, Closure f = null->
                elasticSearchService.search(params + indexAndType, q, f)
            }
            domain.metaClass.'static'."$searchMethodName" << { QueryBuilder q, Closure f = null, Map params = [:] ->
                elasticSearchService.search(q, f, params + indexAndType)
            }
            domain.metaClass.'static'."$searchMethodName" << { Closure q, f, Map params = [:] ->
                elasticSearchService.search(q, f, params + indexAndType)
            }
            domain.metaClass.'static'."$searchMethodName" << { Map params, Closure q, f ->
                elasticSearchService.search(params + indexAndType, q, f)
            }
            domain.metaClass.'static'."$searchMethodName" << { Map params, QueryBuilder q, f = null->
                elasticSearchService.search(params + indexAndType, q, f)
            }
            domain.metaClass.'static'."$searchMethodName" << { QueryBuilder q, f = null, Map params = [:] ->
                elasticSearchService.search(q, f, params + indexAndType)
            }
			domain.metaClass.'static'."$searchMethodName" << { Map params, QueryBuilder q, QueryBuilder f ->
				elasticSearchService.search(params + indexAndType, q, f)
			}
			domain.metaClass.'static'."$searchMethodName" << { QueryBuilder q, QueryBuilder f, Map params = [:] ->
				elasticSearchService.search(q, f, params + indexAndType)
			}

            // Inject the countHits method
            domain.metaClass.'static'."$countHitsMethodName" << { String q, Map params = [:] ->
                elasticSearchService.countHits(q, params + indexAndType)
            }
            domain.metaClass.'static'."$countHitsMethodName" << { Map params = [:], Closure q ->
                elasticSearchService.countHits(params + indexAndType, q)
            }
            domain.metaClass.'static'."$countHitsMethodName" << { Closure q, Map params = [:] ->
                elasticSearchService.countHits(params + indexAndType, q)
            }

            // Inject the search method
            domain.metaClass.static."$searchMethodName" << { String q, Map params = [:] ->
                elasticSearchService.search(q, params + indexAndType)
            }
            domain.metaClass.static."$searchMethodName" << { Map params = [:], Closure q ->
                elasticSearchService.search(params + indexAndType, q)
            }
            domain.metaClass.static."$searchMethodName" << { Closure q, Map params = [:] ->
                elasticSearchService.search(params + indexAndType, q)
            }

            // Inject the countHits method
            domain.metaClass.static."$countHitsMethodName" << { String q, Map params = [:] ->
                elasticSearchService.countHits(q, params + indexAndType)
            }
            domain.metaClass.static."$countHitsMethodName" << { Map params = [:], Closure q ->
                elasticSearchService.countHits(params + indexAndType, q)
            }
            domain.metaClass.static."$countHitsMethodName" << { Closure q, Map params = [:] ->
                elasticSearchService.countHits(params + indexAndType, q)
            }

            // Inject the index method
            // static index() with no arguments index every instances of the domainClass
            domain.metaClass.static.index << { ->
                elasticSearchService.index(class: domainCopy.clazz)
            }
            // static index( domainInstances ) index every instances specified as arguments
            domain.metaClass.static.index << { Collection<GroovyObject> instances ->
                def invalidTypes = instances.any { inst ->
                    inst.class != domainCopy.clazz
                }
                if (!invalidTypes) {
                    elasticSearchService.index(instances)
                } else {
                    throw new IndexException("[${domainCopy.propertyName}] index() method can only be applied its own type. Please use the elasticSearchService if you want to index mixed values.")
                }
            }
            // static index( domainInstances ) index every instances specified as arguments (ellipsis styled)
            domain.metaClass.static.index << { GroovyObject... instances ->
                delegate.metaClass.invokeStaticMethod(domainCopy.clazz, 'index', instances as Collection<GroovyObject>)
            }
            // index() method on domain instance
            domain.metaClass.index << {
                elasticSearchService.index(delegate)
            }

            // Inject the unindex method
            // static unindex() with no arguments unindex every instances of the domainClass
            domain.metaClass.static.unindex << { ->
                elasticSearchService.unindex(class: domainCopy.clazz)
            }
            // static unindex( domainInstances ) unindex every instances specified as arguments
            domain.metaClass.static.unindex << { Collection<GroovyObject> instances ->
                def invalidTypes = instances.any { inst ->
                    inst.class != domainCopy.clazz
                }
                if (!invalidTypes) {
                    elasticSearchService.unindex(instances)
                } else {
                    throw new IndexException("[${domainCopy.propertyName}] unindex() method can only be applied on its own type. Please use the elasticSearchService if you want to unindex mixed values.")
                }
            }
            // static unindex( domainInstances ) unindex every instances specified as arguments (ellipsis styled)
            domain.metaClass.static.unindex << { GroovyObject... instances ->
                delegate.metaClass.invokeStaticMethod(domainCopy.clazz, 'unindex', instances as Collection<GroovyObject>)
            }
            // unindex() method on domain instance
            domain.metaClass.unindex << {
                elasticSearchService.unindex(delegate)
            }
        }
    }

    private static String getSearchablePropertyName(grailsApplication) {
        String searchablePropertyName = grailsApplication.config.elasticSearch.searchableProperty.name

        if (searchablePropertyName) {
            return searchablePropertyName
        }
        //Maintain backwards compatibility. Searchable property name may not be defined
        'searchable'
    }
}
