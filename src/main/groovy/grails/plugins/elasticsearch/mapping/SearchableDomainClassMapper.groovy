/*
 * Copyright 2002-2011 the original author or authors.
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

package grails.plugins.elasticsearch.mapping

import grails.core.GrailsApplication
import grails.core.GrailsDomainClass
import grails.core.GrailsDomainClassProperty
import grails.util.GrailsClassUtils
import groovy.transform.CompileStatic
import org.apache.commons.logging.Log
import org.apache.commons.logging.LogFactory
import org.grails.core.DefaultGrailsDomainClass
import org.grails.core.artefact.DomainClassArtefactHandler
import org.springframework.util.Assert

import java.lang.reflect.Modifier

@CompileStatic
class SearchableDomainClassMapper extends GroovyObjectSupport {
    /**
     * Class mapping properties
     */
    private all = true
    private Boolean root = true

    private Set<String> mappableProperties = []
    private Map<String, SearchableClassPropertyMapping> customMappedProperties = new HashMap<String, SearchableClassPropertyMapping>()
    private GrailsDomainClass grailsDomainClass
    private GrailsApplication grailsApplication
    private only
    private except

    private ConfigObject esConfig

    private Log log = LogFactory.getLog(SearchableDomainClassMapper)

    /**
     * Create closure-based mapping configurator.
     *
     * @param grailsApplication grails app reference
     * @param domainClass Grails domain class to be configured
     * @param esConfig ElasticSearch configuration
     */
    SearchableDomainClassMapper(GrailsApplication grailsApplication, GrailsDomainClass domainClass, ConfigObject esConfig) {
        this.esConfig = esConfig
        this.grailsDomainClass = domainClass
        this.grailsApplication = grailsApplication
    }

    void setAll(all) {
        this.all = all
    }

    void setRoot(Boolean root) {
        this.root = root
    }

    void setOnly(only) {
        this.only = only
    }

    void setExcept(except) {
        this.except = except
    }

    void root(Boolean rootFlag) {
        this.root = rootFlag
    }

    /**
     * @return searchable domain class mapping
     */
    SearchableClassMapping buildClassMapping() {

        String searchablePropertyName = getSearchablePropertyName()

        if (!grailsDomainClass.hasProperty(searchablePropertyName)) {
            return null
        }
        // Process inheritance.
        List<GrailsDomainClass> superMappings = []
        Class<?> currentClass = grailsDomainClass.getClazz()
        superMappings.add(grailsDomainClass)

        while (currentClass != null) {
            currentClass = currentClass.getSuperclass()
            if (currentClass != null && DomainClassArtefactHandler.isDomainClass(currentClass)) {
                GrailsDomainClass superDomainClass = ((GrailsDomainClass) grailsApplication.getArtefact(DomainClassArtefactHandler.TYPE, currentClass.getName()))

                // If the super class is abstract, it needs peculiar processing
                // The abstract class won't be actually mapped to ES, but the concrete subclasses will have to inherit
                // the searchable mapping options.
                if (superDomainClass == null && Modifier.isAbstract(currentClass.getModifiers())) {
                    // We create a temporary dummy GrailsDomainClass instance for this abstract class
                    superDomainClass = new DefaultGrailsDomainClass(currentClass)
                } else {
                    // If superDomainClass is null & not abstract, then we won't process this class
                    break
                }

                if (superDomainClass.hasProperty(searchablePropertyName) &&
                        superDomainClass.getPropertyValue(searchablePropertyName).equals(Boolean.FALSE)) {
                    // hierarchy explicitly terminated. Do not browse any more properties.
                    break
                }
                superMappings.add(superDomainClass)
                if (superDomainClass.isRoot()) {
                    break
                }
            }
        }

        Collections.reverse(superMappings)

        for (GrailsDomainClassProperty prop : getDomainProperties(grailsDomainClass)) {
            mappableProperties.add(prop.getName())
        }

        // !!!! Allow explicit identifier indexing ONLY when defined with custom attributes.
        mappableProperties.add(grailsDomainClass.getIdentifier().getName())

        log.debug("Identified the following properties as candidates for mapping: ${mappableProperties}")

        // Process inherited mappings in reverse order.
        for (GrailsDomainClass domainClass : superMappings) {
            if (domainClass.hasProperty(searchablePropertyName)) {
                Object searchable = domainClass.getPropertyValue(searchablePropertyName)
                if (searchable instanceof Boolean) {
                    buildDefaultMapping(domainClass)
                } else if (searchable instanceof java.util.LinkedHashMap) {
                    Set<String> inheritedProperties = getInheritedProperties(domainClass)
                    buildHashMapMapping((LinkedHashMap) searchable, domainClass, inheritedProperties)
                } else if (searchable instanceof Closure) {
                    Set<String> inheritedProperties = getInheritedProperties(domainClass)
                    buildClosureMapping(domainClass, searchable as Closure, inheritedProperties)
                } else {
                    throw new IllegalArgumentException("'$searchablePropertyName' property has unknown type: " + searchable.getClass())
                }
            }
        }

        // Populate default settings.
        // Clean out any per-property specs not allowed by 'only', 'except' rules.

        customMappedProperties.keySet().retainAll(mappableProperties)
        mappableProperties.remove(grailsDomainClass.getIdentifier().getName())

        for (String propertyName : mappableProperties) {
            SearchableClassPropertyMapping scpm = customMappedProperties.get(propertyName)
            if (scpm == null) {
                scpm = new SearchableClassPropertyMapping(grailsDomainClass.getPropertyByName(propertyName))
                customMappedProperties.put(propertyName, scpm)
            }
        }

        SearchableClassMapping scm = new SearchableClassMapping(grailsDomainClass, customMappedProperties.values())
        scm.setRoot(root)
        scm.setAll(all)
        return scm
    }

    private Set<String> getInheritedProperties(GrailsDomainClass domainClass) {
        // check which properties belong to this domain class ONLY
        Set<String> inheritedProperties = []
        for (GrailsDomainClassProperty prop : getDomainProperties(domainClass)) {
            if (GrailsClassUtils.isPropertyInherited(domainClass.getClazz(), prop.getName())) {
                inheritedProperties.add(prop.getName())
            }
        }
        return inheritedProperties
    }

    void buildDefaultMapping(GrailsDomainClass grailsDomainClass) {

        for (GrailsDomainClassProperty property : getDomainProperties(grailsDomainClass)) {
            //noinspection unchecked
            List<String> defaultExcludedProperties = (List<String>) esConfig.get("defaultExcludedProperties")
            if (defaultExcludedProperties == null || !defaultExcludedProperties.contains(property.getName())) {
                customMappedProperties.put(property.getName(), new SearchableClassPropertyMapping(property))
            }
        }
    }

    void buildClosureMapping(GrailsDomainClass grailsDomainClass, Closure searchable, Set<String> inheritedProperties) {
        assert searchable != null

        // Build user-defined specific mappings
        Closure closure = (Closure) searchable.clone()
        closure.setDelegate(this)
        closure.call()

        buildMappingFromOnlyExcept(grailsDomainClass, inheritedProperties)
    }

    void buildHashMapMapping(LinkedHashMap map, GrailsDomainClass domainClass, Set<String> inheritedProperties) {
        // Support old searchable-plugin syntax ([only: ['category', 'title']] or [except: 'createdAt'])
        only = map.containsKey("only") ? map.get("only") : null
        except = map.containsKey("except") ? map.get("except") : null
        buildMappingFromOnlyExcept(domainClass, inheritedProperties)
    }

    private void buildMappingFromOnlyExcept(GrailsDomainClass domainClass, Set<String> inheritedProperties) {
        Set<String> propsOnly = convertToSet(only)
        Set<String> propsExcept = convertToSet(except)
        if (!propsOnly.isEmpty() && !propsExcept.isEmpty()) {
            throw new IllegalArgumentException("Both 'only' and 'except' were used in '${grailsDomainClass.getPropertyName()}#${getSearchablePropertyName()}': provide one or neither but not both")
        }

        Boolean alwaysInheritProperties = (Boolean) esConfig.get("alwaysInheritProperties")
        boolean inherit = alwaysInheritProperties != null && alwaysInheritProperties

        def defaultExcludedProperties = esConfig.get("defaultExcludedProperties")
        if(defaultExcludedProperties instanceof Collection) {
            log.debug("Removing default excluded properties ${defaultExcludedProperties} from mappable properties for class ${domainClass.clazz} : ${mappableProperties}")
            mappableProperties.removeAll(defaultExcludedProperties)
        }

        // Remove all properties that may be in the "except" rule
        if (!propsExcept.isEmpty()) {
            log.debug("'except' found on class ${domainClass.clazz}. Removing properties from mappings : ${propsExcept}")
            mappableProperties.removeAll(propsExcept)
        }
        // Only keep the properties specified in the "only" rule
        if (!propsOnly.isEmpty()) {
            log.debug("'only' found on class ${domainClass.clazz}.")
            // If we have inherited properties, we keep them nonetheless
            if (inherit) {
                log.debug("'only' found on class ${domainClass.clazz}. Keeping inherited properties : ${inheritedProperties}")
                mappableProperties.retainAll(inheritedProperties)
            } else {
                mappableProperties.clear()
            }
            log.debug("Including properties defined in 'only' : ${propsOnly}")
            mappableProperties.addAll(propsOnly)
        }

        log.debug("Properties to map for class ${domainClass.clazz} are: ${mappableProperties}")
    }

    /**
     * Invoked by 'searchable' closure.
     *
     * @param name synthetic method name
     * @param args method arguments.
     * @return <code>null</code>
     */
    def invokeMethod(String name, args) {
        // Custom properties mapping options
        GrailsDomainClassProperty property = grailsDomainClass.getPropertyByName(name)
        Assert.notNull(property, "Unable to find property [$name] used in [$grailsDomainClass.propertyName]#${getSearchablePropertyName()}].")

        // Check if we already has mapping for this property.
        SearchableClassPropertyMapping propertyMapping = customMappedProperties.get(name)
        if (propertyMapping == null) {
            propertyMapping = new SearchableClassPropertyMapping(property)
            customMappedProperties.put(name, propertyMapping)
        }
        //noinspection unchecked
        propertyMapping.addAttributes((Map<String, Object>) ((Object[]) args)[0])
        return null
    }

    private Set<String> convertToSet(arg) {
        if (arg == null) {
            return Collections.emptySet()
        }
        if (arg instanceof String) {
            return Collections.singleton(arg)
        }
        if (arg instanceof Object[]) {
            return new HashSet<String>(Arrays.asList(arg as String[]))
        }
        if (arg instanceof Collection) {
            //noinspection unchecked
            return new HashSet<String>(arg)
        }
        throw new IllegalArgumentException("Unknown argument: $arg")
    }

    private String getSearchablePropertyName() {
        def searchablePropertyName = (esConfig.searchableProperty as ConfigObject).name

        //Maintain backwards compatibility. Searchable property name may not be defined
        if (!searchablePropertyName) {
            return 'searchable'
        }
        searchablePropertyName
    }

    private GrailsDomainClassProperty[] getDomainProperties(GrailsDomainClass domainClass) {
        GrailsDomainClassProperty[] properties
        if(esConfig.includeTransients) {
            properties = domainClass.getProperties()
            //These properties are specific to GORM and of no use for search. For backwards compatibility they are not included
            properties = properties - domainClass.getPropertyByName("id") - domainClass.getPropertyByName("version")
        } else {
            properties = domainClass.getPersistentProperties()
        }
        properties
    }
}