package grails.plugins.elasticsearch

import grails.converters.JSON
import grails.core.GrailsApplication
import grails.core.GrailsDomainClass
import grails.test.mixin.integration.Integration
import grails.transaction.Rollback
import grails.util.GrailsNameUtils
import org.elasticsearch.action.admin.cluster.state.ClusterStateRequestBuilder
import org.elasticsearch.action.get.GetRequest
import org.elasticsearch.action.search.SearchRequest
import org.elasticsearch.action.search.SearchType
import org.elasticsearch.client.AdminClient
import org.elasticsearch.client.ClusterAdminClient
import org.elasticsearch.cluster.ClusterState
import org.elasticsearch.cluster.metadata.IndexMetaData
import org.elasticsearch.cluster.metadata.MappingMetaData
import org.elasticsearch.common.unit.DistanceUnit
import org.elasticsearch.index.query.QueryBuilder
import org.elasticsearch.index.query.QueryBuilders
import org.elasticsearch.search.aggregations.AggregationBuilders
import org.elasticsearch.search.builder.SearchSourceBuilder
import org.elasticsearch.search.sort.FieldSortBuilder
import org.elasticsearch.search.sort.SortBuilders
import org.elasticsearch.search.sort.SortOrder
import org.grails.core.DefaultGrailsDomainClass
import org.grails.web.json.JSONObject
import org.springframework.beans.factory.annotation.Autowired
import spock.lang.Specification
import test.*
import test.custom.id.Toy

import java.math.RoundingMode

@Integration
@Rollback
class ElasticSearchServiceIntegrationSpec extends Specification {

    @Autowired
    ElasticSearchService elasticSearchService
    @Autowired
    ElasticSearchAdminService elasticSearchAdminService
    @Autowired
    ElasticSearchHelper elasticSearchHelper
    @Autowired
    GrailsApplication grailsApplication

    static Boolean isSetup = false

    /*
     * This test class doesn't delete any ElasticSearch indices, because that would also delete the mapping.
     * Be aware of this when indexing new objects.
     */

    void setup() {
        /*
        *  This is workaround due to issue with Grails3 and springbboot, otherwise we could have added in setupSpec
        * */
        if (!isSetup) {
            isSetup = true
            setupData()
        }
    }

    void setupData() {
        Product product01 = new Product(name: 'horst', price: 3.95)
        product01.save(failOnError: true)

        Product product02 = new Product(name: 'hobbit', price: 5.99)
        product02.save(failOnError: true)

        Product product03 = new Product(name: 'best', price: 10.99)
        product03.save(failOnError: true)

        Product product04 = new Product(name: 'high and supreme', price: 45.50)
        product04.save(failOnError: true)
        [
                [lat: 48.13, lon: 11.60, name: '81667'],
                [lat: 48.19, lon: 11.65, name: '85774'],
                [lat: 47.98, lon: 10.18, name: '87700']
        ].each {
            GeoPoint geoPoint = new GeoPoint(lat: it.lat, lon: it.lon).save(failOnError: true)
            new Building(name: "${it.name}", location: geoPoint).save(failOnError: true)
        }

        /*
        * TODO: Need to identify why test cases are not working after removing this.
        * */
        elasticSearchService.index()
        elasticSearchAdminService.refresh()
    }

    void 'Index and un-index a domain object'() {
        given:
        def product = new Product(name: 'myTestProduct')
        product.save(failOnError: true)

        when:
        elasticSearchAdminService.refresh() // Ensure the latest operations have been exposed on the ES instance

        and:
        elasticSearchService.search('myTestProduct', [indices: Product, types: Product]).total == 1

        then:
        elasticSearchService.unindex(product)
        elasticSearchAdminService.refresh()

        and:
        elasticSearchService.search('myTestProduct', [indices: Product, types: Product]).total == 0
    }

    void 'Indexing the same object multiple times updates the corresponding ES entry'() {
        given:
        def product = new Product(name: 'myTestProduct')
        product.save(failOnError: true)

        when:
        elasticSearchService.index(product)
        elasticSearchAdminService.refresh()

        then:
        elasticSearchService.search('myTestProduct', [indices: Product, types: Product]).total == 1

        when:
        product.name = 'newProductName'
        product.save(failOnError: true)
        elasticSearchService.index(product)
        elasticSearchAdminService.refresh()

        then:
        elasticSearchService.search('myTestProduct', [indices: Product, types: Product]).total == 0

        and:
        def result = elasticSearchService.search(product.name, [indices: Product, types: Product])
        result.total == 1
        List<Product> searchResults = result.searchResults
        searchResults[0].name == product.name

    }

    void 'a json object value should be marshalled and de-marshalled correctly'() {
        given:
        def product = new Product(name: 'product with json value')
        product.json = new JSONObject("""
{
    "test": {
        "details": "blah"
    }
}
"""
        )
        product.save(failOnError: true)

        elasticSearchService.index(product)
        elasticSearchAdminService.refresh()

        when:
        def result = elasticSearchService.search(product.name, [indices: Product, types: Product])

        then:
        result.total == 1
        List<Product> searchResults = result.searchResults
        searchResults[0].name == product.name
    }

    void 'should marshal the alias field and unmarshal correctly (ignore alias)'() {
        given:
        def location = new GeoPoint(
                lat: 53.00,
                lon: 10.00
        ).save(failOnError: true)
        def building = new Building(
                name: 'WatchTower',
                location: location
        ).save(failOnError: true)
        building.save(failOnError: true)

        elasticSearchService.index(building)
        elasticSearchAdminService.refresh()

        when:
        def result = elasticSearchService.search(building.name, [indices: Building, types: Building])

        then:
        result.total == 1
        List<Building> searchResults = result.searchResults
        searchResults[0].name == building.name
    }

    void 'a date value should be marshalled and de-marshalled correctly'() {
        Date date = new Date()
        given:
        def product = new Product(
                name: 'product with date value',
                date: date
        ).save(failOnError: true)

        elasticSearchService.index(product)
        elasticSearchAdminService.refresh()

        when:
        def result = elasticSearchService.search(product.name, [indices: Product, types: Product])

        then:
        result.total == 1
        List<Product> searchResults = result.searchResults
        searchResults[0].name == product.name
        searchResults[0].date == product.date
    }

    void 'a geo point location is marshalled and de-marshalled correctly'() {
        given:
        def location = new GeoPoint(
                lat: 53.00,
                lon: 10.00
        ).save(failOnError: true)

        def building = new Building(
                name: 'EvileagueHQ',
                location: location
        ).save(failOnError: true)

        elasticSearchService.index(building)
        elasticSearchAdminService.refresh()

        when:
        def result = elasticSearchService.search('EvileagueHQ', [indices: Building, types: Building])

        then:
        elasticSearchHelper.elasticSearchClient.admin().indices()

        result.total == 1
        List<Building> searchResults = result.searchResults
        def resultLocation = searchResults[0].location
        resultLocation.lat == location.lat
        resultLocation.lon == location.lon
    }

    void 'a geo point is mapped correctly'() {

        when:
        GeoPoint location = new GeoPoint(
                lat: 53.00,
                lon: 10.00
        ).save(failOnError: true)

        Building building = new Building(
                location: location
        ).save(failOnError: true)

        elasticSearchService.index(building)
        elasticSearchAdminService.refresh()

        then:
        def mapping = getFieldMappingMetaData('test', 'building').sourceAsMap
        mapping.(properties).location.type == 'geo_point'
    }

    void 'search with geo distance filter'() {
        given: 'a building with a geo point location'
        GeoPoint geoPoint = new GeoPoint(
                lat: 50.1,
                lon: 13.3
        ).save(failOnError: true)

        def building = new Building(
                name: 'Test Product',
                location: geoPoint
        ).save(failOnError: true)

        elasticSearchService.index(building)
        elasticSearchAdminService.refresh()

        when: 'a geo distance filter search is performed'

        Map params = [indices: Building, types: Building]
        QueryBuilder query = QueryBuilders.matchAllQuery()
        def location = '50, 13'

        Closure filter = {
            'geo_distance'(
                    'distance': '50km',
                    'location': location
            )
        }

        def result = elasticSearchService.search(params, query, filter)

        then: 'the building should be found'
        1 == result.total
        List<Building> searchResults = result.searchResults
        searchResults[0].id == building.id
    }

    void 'searching with filtered query'() {
        given: 'some products'
        def wurmProduct = new Product(name: 'wurm', price: 2.00)
        wurmProduct.save(failOnError: true)

        def hansProduct = new Product(name: 'hans', price: 0.5)
        hansProduct.save(failOnError: true)

        def fooProduct = new Product(name: 'foo', price: 5.0)
        fooProduct.save(failOnError: true)

        elasticSearchService.index(wurmProduct, hansProduct, fooProduct)
        elasticSearchAdminService.refresh()

        when: 'searching for a price'
        def result = elasticSearchService.search(QueryBuilders.matchAllQuery(), QueryBuilders.rangeQuery("price").gte(1.99).lte(2.3))

        then: "the result should be product 'wurm'"
        result.total == 1
        List<Product> searchResults = result.searchResults
        searchResults[0].name == wurmProduct.name
    }

    void 'searching with a FilterBuilder filter and a Closure query'() {
        when: 'searching for a price'
        QueryBuilder filter = QueryBuilders.rangeQuery("price").gte(1.99).lte(2.3)
        def result = elasticSearchService.search(QueryBuilders.matchAllQuery(), filter)

        then: "the result should be product 'wurm'"
        result.total == 1
        List<Product> searchResults = result.searchResults
        searchResults[0].name == "wurm"
    }

    void 'searching with a FilterBuilder filter and a QueryBuilder query'() {
        when: 'searching for a price'
        QueryBuilder filter = QueryBuilders.rangeQuery("price").gte(1.99).lte(2.3)
        def result = elasticSearchService.search(QueryBuilders.matchAllQuery(), filter)

        then: "the result should be product 'wurm'"
        result.total == 1
        List<Product> searchResults = result.searchResults
        searchResults[0].name == "wurm"
    }

    void 'searching with wildcards in query at first position'() {

        when: 'search with asterisk at first position'
        elasticSearchService.index(Product)
        elasticSearchAdminService.refresh()
        Map params = [indices: Product, types: Product]
        def result = elasticSearchService.search({
            wildcard(name: '*st')
        }, params)

        then: 'the result should contain 2 products'
        result.total == 2
        List<Product> searchResults = result.searchResults
        searchResults*.name.containsAll('best', 'horst')
    }

    void 'searching with wildcards in query at last position'() {
        when: 'search with asterisk at last position'
        elasticSearchService.index(Product)
        elasticSearchAdminService.refresh()

        Map params2 = [indices: Product, types: Product]
        def result2 = elasticSearchService.search({
            wildcard(name: 'ho*')
        }, params2)

        then: 'the result should return 2 products'
        result2.total == 2
        List<Product> searchResults2 = result2.searchResults
        searchResults2*.name.containsAll('horst', 'hobbit')
    }

    void 'searching with wildcards in query in between position'() {
        when: 'search with asterisk in between position'

        elasticSearchService.index(Product)
        elasticSearchAdminService.refresh()
        Map params3 = [indices: Product, types: Product]
        def result3 = elasticSearchService.search({
            wildcard(name: 's*eme')
        }, params3)

        then: 'the result should return 1 product'
        result3.total == 1
        List<Product> searchResults3 = result3.searchResults
        searchResults3[0].name == 'high and supreme'
    }

    void 'searching for special characters in data pool'() {

        given: 'some products'
        def product01 = new Product(name: 'ästhätik', price: 3.95)
        product01.save(failOnError: true)

        elasticSearchService.index(product01)
        elasticSearchAdminService.refresh()

        when: "search for 'a umlaut' "

        def result = elasticSearchService.search({
            match(name: 'ästhätik')
        })

        then: 'the result should contain 1 product'
        result.total == 1
        List<Product> searchResults = result.searchResults
        searchResults[0].name == product01.name
    }

    void 'searching for features of the parent element from the actual element'() {

        given: 'parent and child elements'

        def parentParentElement = new Store(name: 'Eltern-Elternelement', owner: 'Horst')
        parentParentElement.save(failOnError: true)
        def parentElement = new Department(name: 'Elternelement', numberOfProducts: 4, store: parentParentElement)
        parentElement.save(failOnError: true)
        def childElement = new Product(name: 'Kindelement', price: 5.00)
        childElement.save(failOnError: true)

        elasticSearchService.index(parentParentElement, parentElement, childElement)
        elasticSearchAdminService.refresh()

        when:
        def result = elasticSearchService.search(
                QueryBuilders.hasParentQuery('store', QueryBuilders.matchQuery('owner', 'Horst')),
                QueryBuilders.matchAllQuery(),
                [indices: Department, types: Department]
        )

        then:
        !result.searchResults.empty
    }

    void 'Paging and sorting through search results'() {
        given: 'a bunch of products'
        def product
        10.times {
            product = new Product(name: "Produkt${it}", price: it).save(failOnError: true, flush: true)
            elasticSearchService.index(product)
        }
        elasticSearchAdminService.refresh()

        when: 'a search is performed'
        def params = [from: 3, size: 2, indices: Product, types: Product, sort: 'name']
        def query = {
            wildcard(name: 'produkt*')
        }
        def result = elasticSearchService.search(query, params)

        then: 'the correct result-part is returned'
        result.total == 10
        result.searchResults.size() == 2
        result.searchResults*.name == ['Produkt3', 'Produkt4']
    }

    void 'Multiple sorting through search results'() {
        given: 'a bunch of products'
        def product
        2.times { int i ->
            2.times { int k ->
                product = new Product(name: "Yogurt$i", price: k).save(failOnError: true, flush: true)
                elasticSearchService.index(product)
            }
        }
        elasticSearchAdminService.refresh()

        when: 'a search is performed'
        def sort1 = new FieldSortBuilder('name').order(SortOrder.ASC)
        def sort2 = new FieldSortBuilder('price').order(SortOrder.DESC)
        def params = [indices: Product, types: Product, sort: [sort1, sort2]]
        def query = {
            wildcard(name: 'yogurt*')
        }
        def result = elasticSearchService.search(query, params)

        then: 'the correct result-part is returned'
        result.searchResults.size() == 4
        result.searchResults*.name == ['Yogurt0', 'Yogurt0', 'Yogurt1', 'Yogurt1']
        result.searchResults*.price == [1, 0, 1, 0]

        when: 'another search is performed'
        sort1 = new FieldSortBuilder('name').order(SortOrder.DESC)
        sort2 = new FieldSortBuilder('price').order(SortOrder.ASC)
        params = [indices: Product, types: Product, sort: [sort1, sort2]]
        query = {
            wildcard(name: 'yogurt*')
        }
        result = elasticSearchService.search(query, params)

        then: 'the correct result-part is returned'
        result.total == 4
        result.searchResults.size() == 4
        result.searchResults*.name == ['Yogurt1', 'Yogurt1', 'Yogurt0', 'Yogurt0']
        result.searchResults*.price == [0, 1, 0, 1]
    }

    void 'A search with Uppercase Characters should return appropriate results'() {
        given: 'a product with an uppercase name'
        def product = new Product(name: 'Großer Kasten', price: 0.85).save(failOnError: true, flush: true)
        elasticSearchService.index(product)
        elasticSearchAdminService.refresh()

        when: 'a search is performed'
        def params = [indices: Product, types: Product]
        def query = {
            match('name': 'Großer')
        }
        def result = elasticSearchService.search(query, params)

        then: 'the correct result-part is returned'
        result.total == 1
        result.searchResults.size() == 1
        result.searchResults*.name == ['Großer Kasten']
    }

    void 'a geo distance search finds geo points at varying distances'() {

        given:
        def buildings = Building.list()
        buildings.each {
            it.delete()
        }

        when: 'a geo distance search is performed'
        Map params = [indices: Building, types: Building]
        QueryBuilder query = QueryBuilders.matchAllQuery()
        def location = [lat: 48.141, lon: 11.57]

        Closure filter = {
            geo_distance(
                    'distance': distance,
                    'location': location
            )
        }
        def result = elasticSearchService.search(params, query, filter)

        then: 'all geo points in the search radius are found'
        List<Building> searchResults = result.searchResults

        (postalCodesFound.empty && searchResults.empty) || searchResults.each { searchResult ->
            searchResult.name in postalCodesFound
        }

        where:
        distance || postalCodesFound
        '1km'     | []
        '5km'     | ['81667']
        '20km'    | ['81667', '85774']
        '1000km'  | ['81667', '85774', '87700']
    }

    void 'A search with lowercase Characters should return appropriate results'() {
        given: 'a product with a lowercase name'
        def product = new Product(name: 'KLeiner kasten', price: 0.45).save(failOnError: true, flush: true)
        elasticSearchService.index(product)
        elasticSearchAdminService.refresh()

        when: 'a search is performed'
        def params = [indices: Product, types: Product]
        def query = {
            wildcard('name': 'klein*')
        }
        def result = elasticSearchService.search(query, params)

        then: 'the correct result-part is returned'
        result.total == 1
        result.searchResults.size() == 1
        result.searchResults*.name == ['KLeiner kasten']
    }

    void 'the distances are returned'() {
        given:
        def buildings = Building.list()
        buildings.each {
            it.delete()
        }

        when: 'a geo distance search is sorted by distance'

        def sortBuilder = SortBuilders.geoDistanceSort('location').
                point(48.141, 11.57).
                unit(DistanceUnit.KILOMETERS).
                order(SortOrder.ASC)

        Map params = [indices: Building, types: Building, sort: sortBuilder]
        QueryBuilder query = QueryBuilders.matchAllQuery()
        def location = [lat: 48.141, lon: 11.57]

        Closure filter = {
            geo_distance(
                    'distance': '5km',
                    'location': location
            )
        }
        def result = elasticSearchService.search(params, query, filter)

        and:
        List<Building> searchResults = result.searchResults
        //Avoid double precission issues
        def sortResults = result.sort.(searchResults[0].id).collect {(it as BigDecimal).setScale(4, RoundingMode.HALF_UP) }

        then: 'all geo points in the search radius are found'
        sortResults == [2.5383]
    }

    void 'Component as an inner object'() {
        given:
        def mal = new Person(firstName: 'Malcolm', lastName: 'Reynolds').save(flush: true)
        def spaceship = new Spaceship(name: 'Serenity', captain: mal).save(flush: true)
        elasticSearchService.index(spaceship)
        elasticSearchAdminService.refresh()

        when:
        def search = elasticSearchService.search('serenity', [indices: Spaceship, types: Spaceship])

        then:
        search.total == 1

        def result = search.searchResults.first()
        result.name == 'Serenity'
        result.captain.firstName == 'Malcolm'
        result.captain.lastName == 'Reynolds'
    }

    void 'dynamicly mapped JSON strings should be searchable'() {
        given: 'A Spaceship with some cool canons'
        def spaceship = new Spaceship(name: 'Spaceball One', captain: new Person(firstName: 'Dark', lastName: 'Helmet').save())
        def data = [
                engines   : [
                        [name: "Primary", maxSpeed: 'Ludicrous Speed'],
                        [name: "Secondary", maxSpeed: 'Ridiculous Speed'],
                        [name: "Tertiary", maxSpeed: 'Light Speed'],
                        [name: "Main", maxSpeed: 'Sub-Light Speed'],
                ],
                facilities: ['Shopping Mall', 'Zoo', 'Three-Ring circus']
        ]
        spaceship.shipData = (data as JSON).toString()
        spaceship.save(flush: true, validate: false)
        elasticSearchService.index(spaceship)
        elasticSearchAdminService.refresh()

        when: 'a search is executed'
        def search = elasticSearchService.search([indices: Spaceship, types: Spaceship], {
            bool {
                must {
                    term("shipData.engines.name": 'primary')
                }
            }
        })
        then: "the json data should be searchable as if it was an actual component of the Spaceship"
        search.total == 1
        def result = search.searchResults.first()
        def shipData = JSON.parse(result.shipData)

        result.name == 'Spaceball One'
        shipData.facilities.size() == 3
    }

    void 'Index a domain object with UUID-based id'() {
        given:
        def car = new Toy(name: 'Car', color: "Red")
        car.save(failOnError: true)

        def plane = new Toy(name: 'Plane', color: "Yellow")
        plane.save(failOnError: true)
        elasticSearchService.index([car, plane])
        elasticSearchAdminService.refresh()

        when:
        def search = elasticSearchService.search('Yellow', [indices: Toy, types: Toy])

        then:
        search.total == 1
        search.searchResults[0].id == plane.id
    }

    void 'bulk test'() {
        given:
        (1..1858).each {
            def person = new Person(firstName: 'Person', lastName: 'McNumbery' + it).save(flush: true)
            def spaceShip = new Spaceship(name: 'Ship-' + it, captain: person).save(flush: true)
            println "Created ${it} domains"
        }

        when:
        elasticSearchService.index(Spaceship)
        elasticSearchAdminService.refresh(Spaceship)

        then:
        findFailures().size() == 0
        elasticSearchService.countHits('Ship\\-') == 1858
    }

    void 'Use an aggregation'() {
        given:
        def jim = new Product(name: 'jim', price: 1.99).save(flush: true, failOnError: true)
        def xlJim = new Product(name: 'xl-jim', price: 5.99).save(flush: true, failOnError: true)
        elasticSearchService.index(jim, xlJim)
        elasticSearchAdminService.refresh()

        def query = QueryBuilders.matchQuery('name', 'jim')
        SearchRequest request = new SearchRequest()
        request.searchType SearchType.DFS_QUERY_THEN_FETCH

        SearchSourceBuilder source = new SearchSourceBuilder()
        source.aggregation(AggregationBuilders.max('max_price').field('price'))

        when:
        def search = elasticSearchService.search(request.source(source.query(query)), [indices: Product, types: Product])

        then:
        search.total == 2
        search.aggregations.'max_price'.max == 5.99f
    }

    private def findFailures() {
        def domainClass = new DefaultGrailsDomainClass(Spaceship)
        def failures = []
        def allObjects = Spaceship.list()
        allObjects.each {
            elasticSearchHelper.withElasticSearch { client ->
                GetRequest getRequest = new GetRequest(getIndexName(domainClass), getTypeName(domainClass), it.id.toString());
                def result = client.get(getRequest).actionGet()
                if (!result.isExists()) {
                    failures << it
                }
            }
        }
        failures
    }

    private MappingMetaData getFieldMappingMetaData(String indexName, String typeName) {
        if (elasticSearchAdminService.aliasExists(indexName)) {
            indexName = elasticSearchAdminService.indexPointedBy(indexName)
        }
        AdminClient admin = elasticSearchHelper.elasticSearchClient.admin()
        ClusterAdminClient cluster = admin.cluster()
        ClusterStateRequestBuilder indices = cluster.prepareState().setIndices(indexName)
        ClusterState clusterState = indices.execute().actionGet().state
        IndexMetaData indexMetaData = clusterState.metaData.index(indexName)
        return indexMetaData.mapping(typeName)
    }

    private String getIndexName(GrailsDomainClass domainClass) {
        String name = grailsApplication.config.elasticSearch.index.name ?: domainClass.packageName
        if (name == null || name.length() == 0) {
            name = domainClass.getPropertyName()
        }
        return name.toLowerCase()
    }

    private String getTypeName(GrailsDomainClass domainClass) {
        GrailsNameUtils.getPropertyName(domainClass.clazz)
    }
}
