package grails.plugins.elasticsearch.conversion.unmarshall

import grails.test.mixin.TestMixin
import grails.test.mixin.web.ControllerUnitTestMixin
import org.joda.time.DateTime
import spock.lang.Specification

@TestMixin(ControllerUnitTestMixin)
class DomainClassUnmarshallerSpec extends Specification {

    DomainClassUnmarshaller unmarshaller

    def setup(){
        unmarshaller = new DomainClassUnmarshaller()
    }

    def "when instance is empty and rebuiltProperties is empty, populateUnboundDateTimeProperties sets nothing"(){
        given:
        def instance = new Sample()
        def rebuiltProperties = [:]

        when:
        unmarshaller.populateUnboundDateTimeProperties(instance, rebuiltProperties)

        then:
        instance
        !instance.identifier
        !instance.phone
        !instance.dateCreated
        !instance.lastUpdated
    }

    def "when instance is empty and rebuiltProperties only has properties that do not belong to the instance, populateUnboundDateTimeProperties sets nothing"(){
        given:
        def instance = new Sample()
        def rebuiltProperties = [name: "value", other: "value"]

        when:
        unmarshaller.populateUnboundDateTimeProperties(instance, rebuiltProperties)

        then:
        instance
        !instance.identifier
        !instance.phone
        !instance.dateCreated
        !instance.lastUpdated
    }

    def "when instance is empty and rebuiltProperties only has properties that are not date related, populateUnboundDateTimeProperties sets nothing"(){
        given:
        def instance = new Sample()
        def rebuiltProperties = [identifier: "value", phone: "1234567890"]

        when:
        unmarshaller.populateUnboundDateTimeProperties(instance, rebuiltProperties)

        then:
        instance
        !instance.identifier
        !instance.phone
        !instance.dateCreated
        !instance.lastUpdated
    }

    def "when instance is empty and rebuiltProperties has properties that are DateTime, populateUnboundDateTimeProperties sets only those dates"(){
        given:
        def dateCreated = DateTime.now()
        def lastUpdated = DateTime.now()
        def instance = new Sample()
        def rebuiltProperties = [identifier: "value", phone: "1234567890", dateCreated: dateCreated.toString(), lastUpdated: lastUpdated.toString()]

        when:
        unmarshaller.populateUnboundDateTimeProperties(instance, rebuiltProperties)

        then:
        instance
        !instance.identifier
        !instance.phone
        dateCreated == instance.dateCreated
        lastUpdated == instance.lastUpdated
    }

    def "when instance is empty and rebuiltProperties has properties that are DateTime and Date, populateUnboundDateTimeProperties sets both types of dates"(){
        given:
        def dateCreated = DateTime.now()
        def lastUpdated = DateTime.now()
        def lastSync = new Date()
        def instance = new Sample()
        def rebuiltProperties = [identifier: "value", phone: "1234567890", dateCreated: dateCreated.toString(), lastUpdated: lastUpdated.toString(), lastSync: lastSync]

        when:
        unmarshaller.populateUnboundDateTimeProperties(instance, rebuiltProperties)

        then:
        instance
        !instance.identifier
        !instance.phone
        dateCreated == instance.dateCreated
        lastUpdated == instance.lastUpdated
        lastSync == instance.lastSync
    }

    def "when instance is empty and rebuiltProperties has null properties that are DateTime, populateUnboundDateTimeProperties sets only the non-null dates"(){
        given:
        def dateCreated = DateTime.now()
        def instance = new Sample()
        def rebuiltProperties = [identifier: "value", phone: "1234567890", dateCreated: dateCreated.toString(), lastUpdated: null]

        when:
        unmarshaller.populateUnboundDateTimeProperties(instance, rebuiltProperties)

        then:
        instance
        !instance.identifier
        !instance.phone
        dateCreated == instance.dateCreated
        !instance.lastUpdated
    }

    def "when instance is has date properties, populateUnboundDateTimeProperties does not reset them"(){
        given:
        def dateCreated = DateTime.now()
        def lastUpdated = DateTime.now()
        def lastSync = new Date()
        def instance = new Sample(dateCreated: dateCreated, lastUpdated: lastUpdated, lastSync: lastSync)
        def rebuiltProperties = [identifier: "value", phone: "1234567890", dateCreated: DateTime.now().toString(), lastUpdated: DateTime.now().toString()]

        when:
        unmarshaller.populateUnboundDateTimeProperties(instance, rebuiltProperties)

        then:
        instance
        !instance.identifier
        !instance.phone
        dateCreated == instance.dateCreated
        lastUpdated == instance.lastUpdated
        lastSync == instance.lastSync
    }


    class Sample {

        Long identifier
        String phone
        DateTime dateCreated
        DateTime lastUpdated
        Date lastSync

    }

}


