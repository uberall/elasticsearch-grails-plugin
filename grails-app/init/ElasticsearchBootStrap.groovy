

import grails.plugins.elasticsearch.ElasticSearchBootStrapHelper
import grails.plugins.elasticsearch.mapping.SearchableClassMappingConfigurator
import grails.plugins.elasticsearch.util.DomainDynamicMethodsUtils
import grails.util.Holders

class ElasticsearchBootStrap {

    ElasticSearchBootStrapHelper elasticSearchBootStrapHelper
    SearchableClassMappingConfigurator searchableClassMappingConfigurator

    def grailsApplication
    def applicationContext
    def init = { servletContext ->
        grailsApplication = Holders.grailsApplication
        applicationContext = Holders.applicationContext

        searchableClassMappingConfigurator.configureAndInstallMappings()
        DomainDynamicMethodsUtils.injectDynamicMethods(grailsApplication, applicationContext)
        elasticSearchBootStrapHelper?.bulkIndexOnStartup()
    }
}
