package grails.plugins.elasticsearch

import grails.core.GrailsApplication
import grails.plugins.elasticsearch.mapping.MappingMigrationStrategy
import grails.plugins.elasticsearch.util.ElasticSearchConfigAware
import grails.plugins.elasticsearch.util.IndexNamingUtils
import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import static MappingMigrationStrategy.alias
import static MappingMigrationStrategy.none

/**
 * Created by @marcos-carceles on 13/01/15.
 * Created and exposed as a bean, because Bootstrap cannot be easily tested and invoked from IntegrationSpec
 */
@CompileStatic
class ElasticSearchBootStrapHelper implements ElasticSearchConfigAware {

    private static final Logger LOG = LoggerFactory.getLogger(this)

    GrailsApplication grailsApplication
    ElasticSearchService elasticSearchService
    ElasticSearchAdminService elasticSearchAdminService
    ElasticSearchContextHolder elasticSearchContextHolder

    void bulkIndexOnStartup() {
        def bulkIndexOnStartup = esConfig?.bulkIndexOnStartup
        //Index Content
        if (bulkIndexOnStartup == "deleted") { //Index lost content due to migration
            LOG.debug "Performing bulk indexing of classes requiring index/mapping migration ${elasticSearchContextHolder.indexesRebuiltOnMigration} on their new version."
            Class[] domainsToReindex = elasticSearchContextHolder.findMappedClassesOnIndices(elasticSearchContextHolder.indexesRebuiltOnMigration) as Class[]
            elasticSearchService.index(domainsToReindex)
        } else if (bulkIndexOnStartup) { //Index all
            LOG.debug "Performing bulk indexing."
            elasticSearchService.index()
        }
        //Update index aliases where needed
        MappingMigrationStrategy migrationStrategy = migrationConfig?.strategy ? MappingMigrationStrategy.valueOf(migrationConfig?.strategy as String) : none
        if (migrationStrategy == alias) {
            elasticSearchContextHolder.indexesRebuiltOnMigration.each { String indexName ->
                int latestVersion = elasticSearchAdminService.getLatestVersion(indexName)
                if(!migrationConfig?.disableAliasChange) {
                    elasticSearchAdminService.pointAliasTo IndexNamingUtils.queryingIndexFor(indexName), indexName, latestVersion
                }
                elasticSearchAdminService.pointAliasTo IndexNamingUtils.indexingIndexFor(indexName), indexName, latestVersion
            }
        }
    }
}
