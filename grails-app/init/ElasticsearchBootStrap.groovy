

import grails.plugins.elasticsearch.ElasticSearchBootStrapHelper
import grails.plugins.elasticsearch.mapping.SearchableClassMappingConfigurator

class ElasticsearchBootStrap {

    ElasticSearchBootStrapHelper elasticSearchBootStrapHelper
    SearchableClassMappingConfigurator searchableClassMappingConfigurator

    def init = { servletContext ->

        //Fixes https://github.com/noamt/elasticsearch-grails-plugin/issues/198
        searchableClassMappingConfigurator.configureAndInstallMappings()
        elasticSearchBootStrapHelper?.bulkIndexOnStartup()
    }
}
