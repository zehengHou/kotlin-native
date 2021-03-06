/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

package org.jetbrains.kotlin.backend.konan.objcexport

import org.jetbrains.kotlin.backend.konan.*
import org.jetbrains.kotlin.backend.konan.descriptors.*
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.constants.ArrayValue
import org.jetbrains.kotlin.resolve.constants.KClassValue
import org.jetbrains.kotlin.resolve.descriptorUtil.*
import org.jetbrains.kotlin.resolve.scopes.MemberScope
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.TypeUtils
import org.jetbrains.kotlin.types.typeUtil.supertypes
import org.jetbrains.kotlin.utils.addIfNotNull

interface ObjCExportTranslator {
    fun translateFile(file: SourceFile, declarations: List<CallableMemberDescriptor>): ObjCInterface
    fun translateClass(descriptor: ClassDescriptor): ObjCInterface
    fun translateInterface(descriptor: ClassDescriptor): ObjCProtocol
    fun translateExtensions(classDescriptor: ClassDescriptor, declarations: List<CallableMemberDescriptor>): ObjCInterface
}

interface ObjCExportWarningCollector {
    fun reportWarning(text: String)
    fun reportWarning(method: FunctionDescriptor, text: String)

    object SILENT : ObjCExportWarningCollector {
        override fun reportWarning(text: String) {}
        override fun reportWarning(method: FunctionDescriptor, text: String) {}
    }
}

internal class ObjCExportTranslatorImpl(
        private val generator: ObjCExportHeaderGenerator?,
        val builtIns: KotlinBuiltIns,
        val mapper: ObjCExportMapper,
        val namer: ObjCExportNamer,
        val warningCollector: ObjCExportWarningCollector
) : ObjCExportTranslator {

    private val kotlinAnyName = namer.kotlinAnyName

    internal fun translateKotlinObjCClassAsUnavailableStub(descriptor: ClassDescriptor): ObjCInterface = objCInterface(
            namer.getClassOrProtocolName(descriptor),
            descriptor = descriptor,
            superClass = "NSObject",
            attributes = listOf("unavailable(\"Kotlin subclass of Objective-C class can't be imported\")")
    )

    private fun referenceClass(descriptor: ClassDescriptor): ObjCExportNamer.ClassOrProtocolName {
        assert(mapper.shouldBeExposed(descriptor))
        assert(!descriptor.isInterface)
        generator?.requireClassOrInterface(descriptor)

        return translateClassOrInterfaceName(descriptor).also {
            generator?.referenceClass(it.objCName, descriptor)
        }
    }

    private fun referenceProtocol(descriptor: ClassDescriptor): ObjCExportNamer.ClassOrProtocolName {
        assert(mapper.shouldBeExposed(descriptor))
        assert(descriptor.isInterface)
        generator?.requireClassOrInterface(descriptor)

        return translateClassOrInterfaceName(descriptor).also {
            generator?.referenceProtocol(it.objCName, descriptor)
        }
    }

    private fun translateClassOrInterfaceName(descriptor: ClassDescriptor): ObjCExportNamer.ClassOrProtocolName {
        assert(mapper.shouldBeExposed(descriptor))

        return namer.getClassOrProtocolName(descriptor)
    }

    override fun translateInterface(descriptor: ClassDescriptor): ObjCProtocol {
        val name = translateClassOrInterfaceName(descriptor)
        val members: List<Stub<*>> = buildMembers { translateInterfaceMembers(descriptor) }
        val superProtocols: List<String> = descriptor.superProtocols

        return objCProtocol(name, descriptor, superProtocols, members)
    }

    private val ClassDescriptor.superProtocols: List<String>
        get() =
            getSuperInterfaces()
                    .asSequence()
                    .filter { mapper.shouldBeExposed(it) }
                    .map {
                        generator?.generateInterface(it)
                        referenceProtocol(it).objCName
                    }
                    .toList()

    override fun translateExtensions(
            classDescriptor: ClassDescriptor,
            declarations: List<CallableMemberDescriptor>
    ): ObjCInterface {
        generator?.generateClass(classDescriptor)

        val name = referenceClass(classDescriptor).objCName
        val members = buildMembers {
            translatePlainMembers(declarations)
        }
        return ObjCInterface(name, categoryName = "Extensions", members = members)
    }

    override fun translateFile(file: SourceFile, declarations: List<CallableMemberDescriptor>): ObjCInterface {
        val name = namer.getFileClassName(file)

        // TODO: stop inheriting KotlinBase.
        val members = buildMembers {
            translatePlainMembers(declarations)
        }
        return objCInterface(
                name,
                superClass = namer.kotlinAnyName.objCName,
                members = members,
                attributes = listOf("objc_subclassing_restricted")
        )
    }

    override fun translateClass(descriptor: ClassDescriptor): ObjCInterface {
        val name = translateClassOrInterfaceName(descriptor)
        val superClass = descriptor.getSuperClassNotAny()

        val superName = if (superClass == null) {
            kotlinAnyName
        } else {
            generator?.generateClass(superClass)
            referenceClass(superClass)
        }

        val superProtocols: List<String> = descriptor.superProtocols
        val members: List<Stub<*>> = buildMembers {
            val presentConstructors = mutableSetOf<String>()

            descriptor.constructors
                    .asSequence()
                    .filter { mapper.shouldBeExposed(it) }
                    .forEach {
                        val selector = getSelector(it)
                        if (!descriptor.isArray) presentConstructors += selector

                        +buildMethod(it, it)
                        if (selector == "init") {
                            +ObjCMethod(it, false, ObjCInstanceType, listOf("new"), emptyList(),
                                    listOf("availability(swift, unavailable, message=\"use object initializers instead\")"))
                        }
                    }

            if (descriptor.isArray || descriptor.kind == ClassKind.OBJECT || descriptor.kind == ClassKind.ENUM_CLASS) {
                +ObjCMethod(null, false, ObjCInstanceType, listOf("alloc"), emptyList(), listOf("unavailable"))

                val parameter = ObjCParameter("zone", null, ObjCRawType("struct _NSZone *"))
                +ObjCMethod(descriptor, false, ObjCInstanceType, listOf("allocWithZone:"), listOf(parameter), listOf("unavailable"))
            }

            // TODO: consider adding exception-throwing impls for these.
            when (descriptor.kind) {
                ClassKind.OBJECT -> {
                    +ObjCMethod(
                            null, false, ObjCInstanceType,
                            listOf(namer.getObjectInstanceSelector(descriptor)), emptyList(),
                            listOf(swiftNameAttribute("init()"))
                    )
                }
                ClassKind.ENUM_CLASS -> {
                    val type = mapType(descriptor.defaultType, ReferenceBridge)

                    descriptor.enumEntries.forEach {
                        val entryName = namer.getEnumEntrySelector(it)
                        +ObjCProperty(entryName, null, type, listOf("class", "readonly"),
                                declarationAttributes = listOf(swiftNameAttribute(entryName)))
                    }
                }
                else -> {
                    // Nothing special.
                }
            }

            // Hide "unimplemented" super constructors:
            superClass?.constructors
                    ?.asSequence()
                    ?.filter { mapper.shouldBeExposed(it) }
                    ?.forEach {
                        val selector = getSelector(it)
                        if (selector !in presentConstructors) {
                            val c = buildMethod(it, it)
                            +ObjCMethod(c.descriptor, c.isInstanceMethod, c.returnType, c.selectors, c.parameters, c.attributes + "unavailable")

                            if (selector == "init") {
                                +ObjCMethod(null, false, ObjCInstanceType, listOf("new"), emptyList(), listOf("unavailable"))
                            }

                            // TODO: consider adding exception-throwing impls for these.
                        }
                    }

            translateClassMembers(descriptor)
        }

        val attributes = if (descriptor.isFinalOrEnum) listOf("objc_subclassing_restricted") else emptyList()

        return objCInterface(
                name,
                descriptor = descriptor,
                superClass = superName.objCName,
                superProtocols = superProtocols,
                members = members,
                attributes = attributes
        )
    }

    private fun ClassDescriptor.getExposedMembers(): List<CallableMemberDescriptor> =
            this.unsubstitutedMemberScope.getContributedDescriptors()
                    .asSequence()
                    .filterIsInstance<CallableMemberDescriptor>()
                    .filter { mapper.shouldBeExposed(it) }
                    .toList()

    private fun StubBuilder.translateClassMembers(descriptor: ClassDescriptor) {
        require(!descriptor.isInterface)
        translateClassMembers(descriptor.getExposedMembers())
    }

    private fun StubBuilder.translateInterfaceMembers(descriptor: ClassDescriptor) {
        require(descriptor.isInterface)
        translateBaseMembers(descriptor.getExposedMembers())
    }

    private class RenderedStub<T: Stub<*>>(val stub: T) {
        private val presentation: String by lazy(LazyThreadSafetyMode.NONE) {
            val listOfLines = StubRenderer.render(stub)
            assert(listOfLines.size == 1)
            listOfLines[0]
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            return other is RenderedStub<*> && presentation == other.presentation
        }

        override fun hashCode(): Int {
            return presentation.hashCode()
        }
    }

    private fun List<CallableMemberDescriptor>.toObjCMembers(
            methodsBuffer: MutableList<FunctionDescriptor>,
            propertiesBuffer: MutableList<PropertyDescriptor>
    ) = this.forEach {
        when (it) {
            is FunctionDescriptor -> methodsBuffer += it
            is PropertyDescriptor -> if (mapper.isObjCProperty(it)) {
                propertiesBuffer += it
            } else {
                methodsBuffer.addIfNotNull(it.getter)
                methodsBuffer.addIfNotNull(it.setter)
            }
            else -> error(it)
        }
    }

    private fun StubBuilder.translateClassMembers(members: List<CallableMemberDescriptor>) {
        // TODO: add some marks about modality.

        val methods = mutableListOf<FunctionDescriptor>()
        val properties = mutableListOf<PropertyDescriptor>()

        members.toObjCMembers(methods, properties)

        collectMethodsOrProperties(methods) { it -> buildAsDeclaredOrInheritedMethods(it.original) }
        collectMethodsOrProperties(properties) { it -> buildAsDeclaredOrInheritedProperties(it.original) }
    }

    private fun StubBuilder.translateBaseMembers(members: List<CallableMemberDescriptor>) {
        // TODO: add some marks about modality.

        val methods = mutableListOf<FunctionDescriptor>()
        val properties = mutableListOf<PropertyDescriptor>()

        members.toObjCMembers(methods, properties)

        methods.retainAll { mapper.isBaseMethod(it) }

        properties.retainAll {
            if (mapper.isBaseProperty(it)) {
                true
            } else {
                methods.addIfNotNull(it.setter?.takeIf(mapper::isBaseMethod))
                false
            }
        }

        translatePlainMembers(methods, properties)
    }

    private fun StubBuilder.translatePlainMembers(members: List<CallableMemberDescriptor>) {
        val methods = mutableListOf<FunctionDescriptor>()
        val properties = mutableListOf<PropertyDescriptor>()

        members.toObjCMembers(methods, properties)

        translatePlainMembers(methods, properties)
    }

    private fun StubBuilder.translatePlainMembers(methods: List<FunctionDescriptor>, properties: List<PropertyDescriptor>) {
        methods.forEach { +buildMethod(it, it) }
        properties.forEach { +buildProperty(it, it) }
    }

    private fun <D : CallableMemberDescriptor, S : Stub<*>> StubBuilder.collectMethodsOrProperties(
            members: List<D>,
            converter: (D) -> Set<RenderedStub<S>>) {
        members.forEach { member ->
            val memberStubs = converter(member).asSequence()

            val filteredMemberStubs = if (member.kind.isReal) {
                memberStubs
            } else {
                val superMembers: Set<RenderedStub<S>> = (member.overriddenDescriptors as Collection<D>)
                        .asSequence()
                        .filter { mapper.shouldBeExposed(it) }
                        .flatMap { converter(it).asSequence() }
                        .toSet()

                memberStubs.filterNot { superMembers.contains(it) }
            }

            this += filteredMemberStubs
                    .map { rendered -> rendered.stub }
                    .toList()
        }
    }

    private val methodToSignatures = mutableMapOf<FunctionDescriptor, Set<RenderedStub<ObjCMethod>>>()
    private val propertyToSignatures = mutableMapOf<PropertyDescriptor, Set<RenderedStub<ObjCProperty>>>()

    private fun buildAsDeclaredOrInheritedMethods(
            method: FunctionDescriptor
    ): Set<RenderedStub<ObjCMethod>> = methodToSignatures.getOrPut(method) {
        val isInterface = (method.containingDeclaration as ClassDescriptor).isInterface

        mapper.getBaseMethods(method)
                .asSequence()
                .distinctBy { namer.getSelector(it) }
                .map { base -> buildMethod((if (isInterface) base else method), base) }
                .map { method -> RenderedStub(method) }
                .toSet()
    }

    private fun buildAsDeclaredOrInheritedProperties(
            property: PropertyDescriptor
    ): Set<RenderedStub<ObjCProperty>> = propertyToSignatures.getOrPut(property) {
        val isInterface = (property.containingDeclaration as ClassDescriptor).isInterface

        mapper.getBaseProperties(property)
                .asSequence()
                .distinctBy { namer.getPropertyName(it) }
                .map { base -> buildProperty((if (isInterface) base else property), base) }
                .map { property -> RenderedStub(property) }
                .toSet()
    }

    // TODO: consider checking that signatures for bases with same selector/name are equal.

    private fun getSelector(method: FunctionDescriptor): String {
        return namer.getSelector(method)
    }

    private fun buildProperty(property: PropertyDescriptor, baseProperty: PropertyDescriptor): ObjCProperty {
        assert(mapper.isBaseProperty(baseProperty))
        assert(mapper.isObjCProperty(baseProperty))

        val getterBridge = mapper.bridgeMethod(baseProperty.getter!!)
        val type = mapReturnType(getterBridge.returnBridge, property.getter!!)
        val name = namer.getPropertyName(baseProperty)

        val attributes = mutableListOf<String>()

        if (!getterBridge.isInstance) {
            attributes += "class"
        }

        val setterName: String?
        val propertySetter = property.setter
        if (propertySetter != null && mapper.shouldBeExposed(propertySetter)) {
            val setterSelector = mapper.getBaseMethods(propertySetter).map { namer.getSelector(it) }.distinct().single()
            setterName = if (setterSelector != "set" + name.capitalize() + ":") setterSelector else null
        } else {
            attributes += "readonly"
            setterName = null
        }

        val getterSelector = getSelector(baseProperty.getter!!)
        val getterName: String? = if (getterSelector != name) getterSelector else null

        return ObjCProperty(name, property, type, attributes, setterName, getterName, listOf(swiftNameAttribute(name)))
    }

    private fun buildMethod(method: FunctionDescriptor, baseMethod: FunctionDescriptor): ObjCMethod {
        fun collectParameters(baseMethodBridge: MethodBridge, method: FunctionDescriptor): List<ObjCParameter> {
            fun unifyName(initialName: String, usedNames: Set<String>): String {
                var unique = initialName
                while (unique in usedNames || unique in cKeywords) {
                    unique += "_"
                }
                return unique
            }

            val valueParametersAssociated = baseMethodBridge.valueParametersAssociated(method)

            val parameters = mutableListOf<ObjCParameter>()

            val usedNames = mutableSetOf<String>()
            valueParametersAssociated.forEach { (bridge: MethodBridgeValueParameter, p: ParameterDescriptor?) ->
                val candidateName: String = when (bridge) {
                    is MethodBridgeValueParameter.Mapped -> {
                        p!!
                        when {
                            p is ReceiverParameterDescriptor -> "receiver"
                            method is PropertySetterDescriptor -> "value"
                            else -> p.name.asString()
                        }
                    }
                    MethodBridgeValueParameter.ErrorOutParameter -> "error"
                    is MethodBridgeValueParameter.KotlinResultOutParameter -> "result"
                }

                val uniqueName = unifyName(candidateName, usedNames)
                usedNames += uniqueName

                val type = when (bridge) {
                    is MethodBridgeValueParameter.Mapped -> mapType(p!!.type, bridge.bridge)
                    MethodBridgeValueParameter.ErrorOutParameter ->
                        ObjCPointerType(ObjCNullableReferenceType(ObjCClassType("NSError")), nullable = true)

                    is MethodBridgeValueParameter.KotlinResultOutParameter ->
                        ObjCPointerType(mapType(method.returnType!!, bridge.bridge), nullable = true)
                }

                parameters += ObjCParameter(uniqueName, p, type)
            }
            return parameters
        }

        assert(mapper.isBaseMethod(baseMethod))

        val baseMethodBridge = mapper.bridgeMethod(baseMethod)

        exportThrownFromThisAndOverridden(method)

        val isInstanceMethod: Boolean = baseMethodBridge.isInstance
        val returnType: ObjCType = mapReturnType(baseMethodBridge.returnBridge, method)
        val parameters = collectParameters(baseMethodBridge, method)
        val selector = getSelector(baseMethod)
        val selectorParts: List<String> = splitSelector(selector)
        val swiftName = namer.getSwiftName(baseMethod)
        val attributes = mutableListOf<String>()

        attributes += swiftNameAttribute(swiftName)

        if (method is ConstructorDescriptor && !method.constructedClass.isArray) { // TODO: check methodBridge instead.
            attributes += "objc_designated_initializer"
        }

        return ObjCMethod(method, isInstanceMethod, returnType, selectorParts, parameters, attributes)
    }

    private fun splitSelector(selector: String): List<String> {
        return if (!selector.endsWith(":")) {
            listOf(selector)
        } else {
            selector.trimEnd(':').split(':').map { "$it:" }
        }
    }

    private val methodsWithThrowAnnotationConsidered = mutableSetOf<FunctionDescriptor>()

    private val uncheckedExceptionClasses = listOf("Error", "RuntimeException").map {
        builtIns.builtInsPackageScope
                .getContributedClassifier(Name.identifier(it), NoLookupLocation.FROM_BACKEND) as ClassDescriptor
    }

    private fun exportThrown(method: FunctionDescriptor) {
        if (!method.kind.isReal) return
        val throwsAnnotation = method.annotations.findAnnotation(KonanFqNames.throws) ?: return

        if (!mapper.doesThrow(method)) {
            warningCollector.reportWarning(method,
                    "@${KonanFqNames.throws.shortName()} annotation should also be added to a base method")
        }

        if (method in methodsWithThrowAnnotationConsidered) return
        methodsWithThrowAnnotationConsidered += method

        val arguments = (throwsAnnotation.allValueArguments.values.single() as ArrayValue).value
        for (argument in arguments) {
            val classDescriptor = TypeUtils.getClassDescriptor((argument as KClassValue).getArgumentType(method.module)) ?: continue

            uncheckedExceptionClasses.firstOrNull { classDescriptor.isSubclassOf(it) }?.let {
                warningCollector.reportWarning(method,
                        "Method is declared to throw ${classDescriptor.fqNameSafe}, " +
                                "but instances of ${it.fqNameSafe} and its subclasses aren't propagated " +
                                "from Kotlin to Objective-C/Swift")
            }

            generator?.requireClassOrInterface(classDescriptor)
        }
    }

    private fun exportThrownFromThisAndOverridden(method: FunctionDescriptor) {
        method.allOverriddenDescriptors.forEach { exportThrown(it) }
    }

    private fun mapReturnType(returnBridge: MethodBridge.ReturnValue, method: FunctionDescriptor): ObjCType = when (returnBridge) {
        MethodBridge.ReturnValue.Void -> ObjCVoidType
        MethodBridge.ReturnValue.HashCode -> ObjCPrimitiveType("NSUInteger")
        is MethodBridge.ReturnValue.Mapped -> mapType(method.returnType!!, returnBridge.bridge)
        MethodBridge.ReturnValue.WithError.Success -> ObjCPrimitiveType("BOOL")
        is MethodBridge.ReturnValue.WithError.RefOrNull -> {
            val successReturnType = mapReturnType(returnBridge.successBridge, method) as? ObjCNonNullReferenceType
                    ?: error("Function is expected to have non-null return type: $method")

            ObjCNullableReferenceType(successReturnType)
        }

        MethodBridge.ReturnValue.Instance.InitResult,
        MethodBridge.ReturnValue.Instance.FactoryResult -> ObjCInstanceType
    }

    internal fun mapReferenceType(kotlinType: KotlinType): ObjCReferenceType =
            mapReferenceTypeIgnoringNullability(kotlinType).let {
                if (kotlinType.binaryRepresentationIsNullable()) {
                    ObjCNullableReferenceType(it)
                } else {
                    it
                }
            }

    internal fun mapReferenceTypeIgnoringNullability(kotlinType: KotlinType): ObjCNonNullReferenceType {
        class TypeMappingMatch(val type: KotlinType, val descriptor: ClassDescriptor, val mapper: CustomTypeMapper)

        val typeMappingMatches = (listOf(kotlinType) + kotlinType.supertypes()).mapNotNull { type ->
            (type.constructor.declarationDescriptor as? ClassDescriptor)?.let { descriptor ->
                mapper.customTypeMappers[descriptor.classId]?.let { mapper ->
                    TypeMappingMatch(type, descriptor, mapper)
                }
            }
        }

        val mostSpecificMatches = typeMappingMatches.filter { match ->
            typeMappingMatches.all { otherMatch ->
                otherMatch.descriptor == match.descriptor ||
                        !otherMatch.descriptor.isSubclassOf(match.descriptor)
            }
        }

        if (mostSpecificMatches.size > 1) {
            val types = mostSpecificMatches.map { it.type }
            val firstType = types[0]
            val secondType = types[1]

            warningCollector.reportWarning(
                    "Exposed type '$kotlinType' is '$firstType' and '$secondType' at the same time. " +
                            "This most likely wouldn't work as expected.")

            // TODO: the same warning for such classes.
        }

        mostSpecificMatches.firstOrNull()?.let {
            return it.mapper.mapType(it.type, this)
        }

        val classDescriptor = kotlinType.getErasedTypeClass()

        // TODO: translate `where T : BaseClass, T : SomeInterface` to `BaseClass* <SomeInterface>`

        // TODO: expose custom inline class boxes properly.
        if (classDescriptor == builtIns.any || classDescriptor.classId in mapper.hiddenTypes || classDescriptor.isInlined()) {
            return ObjCIdType
        }

        if (classDescriptor.defaultType.isObjCObjectType()) {
            return mapObjCObjectReferenceTypeIgnoringNullability(classDescriptor)
        }

        return if (classDescriptor.isInterface) {
            ObjCProtocolType(referenceProtocol(classDescriptor).objCName)
        } else {
            ObjCClassType(referenceClass(classDescriptor).objCName)
        }
    }

    private tailrec fun mapObjCObjectReferenceTypeIgnoringNullability(descriptor: ClassDescriptor): ObjCNonNullReferenceType {
        // TODO: more precise types can be used.

        if (descriptor.isObjCMetaClass()) return ObjCIdType

        if (descriptor.isExternalObjCClass()) {
            return if (descriptor.isInterface) {
                val name = descriptor.name.asString().removeSuffix("Protocol")
                generator?.referenceProtocol(name)
                ObjCProtocolType(name)
            } else {
                val name = descriptor.name.asString()
                generator?.referenceClass(name)
                ObjCClassType(name)
            }
        }

        if (descriptor.isKotlinObjCClass()) {
            return mapObjCObjectReferenceTypeIgnoringNullability(descriptor.getSuperClassOrAny())
        }

        return ObjCIdType
    }

    private fun mapType(kotlinType: KotlinType, typeBridge: TypeBridge): ObjCType = when (typeBridge) {
        ReferenceBridge -> mapReferenceType(kotlinType)
        is ValueTypeBridge -> {
            when (typeBridge.objCValueType) {
                ObjCValueType.BOOL -> ObjCPrimitiveType("BOOL")
                ObjCValueType.UNICHAR -> ObjCPrimitiveType("unichar")
                ObjCValueType.CHAR -> ObjCPrimitiveType("int8_t")
                ObjCValueType.SHORT -> ObjCPrimitiveType("int16_t")
                ObjCValueType.INT -> ObjCPrimitiveType("int32_t")
                ObjCValueType.LONG_LONG -> ObjCPrimitiveType("int64_t")
                ObjCValueType.UNSIGNED_CHAR -> ObjCPrimitiveType("uint8_t")
                ObjCValueType.UNSIGNED_SHORT -> ObjCPrimitiveType("uint16_t")
                ObjCValueType.UNSIGNED_INT -> ObjCPrimitiveType("uint32_t")
                ObjCValueType.UNSIGNED_LONG_LONG -> ObjCPrimitiveType("uint64_t")
                ObjCValueType.FLOAT -> ObjCPrimitiveType("float")
                ObjCValueType.DOUBLE -> ObjCPrimitiveType("double")
                ObjCValueType.POINTER -> ObjCPointerType(ObjCVoidType)
            }
            // TODO: consider other namings.
        }
    }
}

abstract class ObjCExportHeaderGenerator internal constructor(
        val moduleDescriptors: List<ModuleDescriptor>,
        val builtIns: KotlinBuiltIns,
        internal val mapper: ObjCExportMapper,
        val namer: ObjCExportNamer
) {

    constructor(
            moduleDescriptors: List<ModuleDescriptor>,
            builtIns: KotlinBuiltIns,
            topLevelNamePrefix: String
    ) : this(moduleDescriptors, builtIns, topLevelNamePrefix, ObjCExportMapper())

    private constructor(
            moduleDescriptors: List<ModuleDescriptor>,
            builtIns: KotlinBuiltIns,
            topLevelNamePrefix: String,
            mapper: ObjCExportMapper
    ) : this(
            moduleDescriptors,
            builtIns,
            mapper,
            ObjCExportNamerImpl(moduleDescriptors.toSet(), builtIns, mapper, topLevelNamePrefix, local = false)
    )

    constructor(
            moduleDescriptor: ModuleDescriptor,
            builtIns: KotlinBuiltIns,
            topLevelNamePrefix: String = moduleDescriptor.namePrefix
    ) : this(moduleDescriptor, emptyList(), builtIns, topLevelNamePrefix)

    constructor(
            moduleDescriptor: ModuleDescriptor,
            exportedDependencies: List<ModuleDescriptor>,
            builtIns: KotlinBuiltIns,
            topLevelNamePrefix: String = moduleDescriptor.namePrefix
    ) : this(listOf(moduleDescriptor) + exportedDependencies, builtIns, topLevelNamePrefix)

    private val stubs = mutableListOf<Stub<*>>()

    private val classForwardDeclarations = linkedSetOf<String>()
    private val protocolForwardDeclarations = linkedSetOf<String>()
    private val extraClassesToTranslate = mutableSetOf<ClassDescriptor>()

    private val translator = ObjCExportTranslatorImpl(this, builtIns, mapper, namer,
            object : ObjCExportWarningCollector {
                override fun reportWarning(text: String) =
                        this@ObjCExportHeaderGenerator.reportWarning(text)

                override fun reportWarning(method: FunctionDescriptor, text: String) =
                        this@ObjCExportHeaderGenerator.reportWarning(method, text)
            })

    private val generatedClasses = mutableSetOf<ClassDescriptor>()
    private val extensions = mutableMapOf<ClassDescriptor, MutableList<CallableMemberDescriptor>>()
    private val topLevel = mutableMapOf<SourceFile, MutableList<CallableMemberDescriptor>>()

    fun build(): List<String> = mutableListOf<String>().apply {
        add("#import <Foundation/NSArray.h>")
        add("#import <Foundation/NSDictionary.h>")
        add("#import <Foundation/NSError.h>")
        add("#import <Foundation/NSObject.h>")
        add("#import <Foundation/NSSet.h>")
        add("#import <Foundation/NSString.h>")
        add("#import <Foundation/NSValue.h>")
        add("")

        if (classForwardDeclarations.isNotEmpty()) {
            add("@class ${classForwardDeclarations.joinToString()};")
            add("")
        }

        if (protocolForwardDeclarations.isNotEmpty()) {
            add("@protocol ${protocolForwardDeclarations.joinToString()};")
            add("")
        }

        add("NS_ASSUME_NONNULL_BEGIN")
        add("")

        stubs.forEach {
            addAll(StubRenderer.render(it))
            add("")
        }

        add("NS_ASSUME_NONNULL_END")
    }

    internal fun buildInterface(): ObjCExportedInterface {
        val headerLines = build()
        return ObjCExportedInterface(generatedClasses, extensions, topLevel, headerLines, namer, mapper)
    }

    protected abstract fun reportWarning(text: String)

    protected abstract fun reportWarning(method: FunctionDescriptor, text: String)


    fun translateModule(): List<Stub<*>> {
        // TODO: make the translation order stable
        // to stabilize name mangling.

        stubs.add(objCInterface(namer.kotlinAnyName, superClass = "NSObject", members = buildMembers {
            +ObjCMethod(null, true, ObjCInstanceType, listOf("init"), emptyList(), listOf("unavailable"))
            +ObjCMethod(null, false, ObjCInstanceType, listOf("new"), emptyList(), listOf("unavailable"))
            +ObjCMethod(null, false, ObjCVoidType, listOf("initialize"), emptyList(), listOf("objc_requires_super"))
        }))

        // TODO: add comment to the header.
        stubs.add(ObjCInterface(
                namer.kotlinAnyName.objCName,
                superProtocols = listOf("NSCopying"),
                categoryName = "${namer.kotlinAnyName.objCName}Copying"
        ))

        // TODO: only if appears
        stubs.add(objCInterface(
                namer.mutableSetName,
                generics = listOf("ObjectType"),
                superClass = "NSMutableSet<ObjectType>"
        ))

        // TODO: only if appears
        stubs.add(objCInterface(
                namer.mutableMapName,
                generics = listOf("KeyType", "ObjectType"),
                superClass = "NSMutableDictionary<KeyType, ObjectType>"
        ))

        stubs.add(ObjCInterface("NSError", categoryName = "NSErrorKotlinException", members = buildMembers {
            +ObjCProperty("kotlinException", null, ObjCNullableReferenceType(ObjCIdType), listOf("readonly"))
        }))

        genKotlinNumbers()

        val packageFragments = moduleDescriptors.flatMap { it.getPackageFragments() }

        packageFragments.forEach { packageFragment ->
            packageFragment.getMemberScope().getContributedDescriptors()
                    .asSequence()
                    .filterIsInstance<CallableMemberDescriptor>()
                    .filter { mapper.shouldBeExposed(it) }
                    .forEach {
                        val classDescriptor = mapper.getClassIfCategory(it)
                        if (classDescriptor != null) {
                            extensions.getOrPut(classDescriptor, { mutableListOf() }) += it
                        } else {
                            topLevel.getOrPut(it.findSourceFile(), { mutableListOf() }) += it
                        }
                    }

        }

        fun MemberScope.translateClasses() {
            getContributedDescriptors()
                    .asSequence()
                    .filterIsInstance<ClassDescriptor>()
                    .forEach {
                        if (mapper.shouldBeExposed(it)) {
                            if (it.isInterface) {
                                generateInterface(it)
                            } else {
                                generateClass(it)
                            }

                            it.unsubstitutedMemberScope.translateClasses()
                        } else if (it.isKotlinObjCClass() && mapper.shouldBeVisible(it)) {
                            assert(!it.isInterface)
                            stubs += translator.translateKotlinObjCClassAsUnavailableStub(it)
                        }
                    }
        }

        packageFragments.forEach { packageFragment ->
            packageFragment.getMemberScope().translateClasses()
        }

        extensions.forEach { classDescriptor, declarations ->
            generateExtensions(classDescriptor, declarations)
        }

        topLevel.forEach { sourceFile, declarations ->
            generateFile(sourceFile, declarations)
        }

        while (extraClassesToTranslate.isNotEmpty()) {
            val descriptor = extraClassesToTranslate.first()
            extraClassesToTranslate -= descriptor
            if (descriptor.isInterface) {
                generateInterface(descriptor)
            } else {
                generateClass(descriptor)
            }
        }

        return stubs
    }

    private fun genKotlinNumbers() {
        val members = buildMembers {
            NSNumberKind.values().forEach {
                +nsNumberFactory(it, listOf("unavailable"))
            }
            NSNumberKind.values().forEach {
                +nsNumberInit(it, listOf("unavailable"))
            }
        }
        stubs.add(objCInterface(
                namer.kotlinNumberName,
                superClass = "NSNumber",
                members = members
        ))

        NSNumberKind.values().forEach {
            if (it.mappedKotlinClassId != null) {
                stubs += genKotlinNumber(it.mappedKotlinClassId, it)
            }
        }
    }

    private fun genKotlinNumber(kotlinClassId: ClassId, kind: NSNumberKind): ObjCInterface {
        val name = namer.numberBoxName(kotlinClassId)

        val members = buildMembers {
            +nsNumberFactory(kind)
            +nsNumberInit(kind)
        }
        return objCInterface(
                name,
                superClass = namer.kotlinNumberName.objCName,
                members = members
        )
    }

    private fun nsNumberInit(kind: NSNumberKind, attributes: List<String> = emptyList()): ObjCMethod {
        return ObjCMethod(
                null,
                false,
                ObjCInstanceType,
                listOf(kind.factorySelector),
                listOf(ObjCParameter("value", null, kind.objCType)),
                attributes
        )
    }

    private fun nsNumberFactory(kind: NSNumberKind, attributes: List<String> = emptyList()): ObjCMethod {
        return ObjCMethod(
                null,
                true,
                ObjCInstanceType,
                listOf(kind.initSelector),
                listOf(ObjCParameter("value", null, kind.objCType)),
                attributes
        )
    }

    private fun generateFile(sourceFile: SourceFile, declarations: List<CallableMemberDescriptor>) {
        stubs.add(translator.translateFile(sourceFile, declarations))
    }

    private fun generateExtensions(classDescriptor: ClassDescriptor, declarations: List<CallableMemberDescriptor>) {
        stubs.add(translator.translateExtensions(classDescriptor, declarations))
    }

    internal fun generateClass(descriptor: ClassDescriptor) {
        if (!generatedClasses.add(descriptor)) return
        stubs.add(translator.translateClass(descriptor))
    }

    internal fun generateInterface(descriptor: ClassDescriptor) {
        if (!generatedClasses.add(descriptor)) return
        stubs.add(translator.translateInterface(descriptor))
    }

    internal fun requireClassOrInterface(descriptor: ClassDescriptor) {
        if (descriptor !in generatedClasses) {
            extraClassesToTranslate += descriptor
        }
    }

    internal fun referenceClass(objCName: String, descriptor: ClassDescriptor? = null) {
        if (descriptor !in generatedClasses) classForwardDeclarations += objCName
    }

    internal fun referenceProtocol(objCName: String, descriptor: ClassDescriptor? = null) {
        if (descriptor !in generatedClasses) protocolForwardDeclarations += objCName
    }
}

private fun objCInterface(
        name: ObjCExportNamer.ClassOrProtocolName,
        generics: List<String> = emptyList(),
        descriptor: ClassDescriptor? = null,
        superClass: String? = null,
        superProtocols: List<String> = emptyList(),
        members: List<Stub<*>> = emptyList(),
        attributes: List<String> = emptyList()
): ObjCInterface = ObjCInterface(
        name.objCName,
        generics,
        descriptor,
        superClass,
        superProtocols,
        null,
        members,
        attributes + name.toNameAttributes()
)

private fun objCProtocol(
        name: ObjCExportNamer.ClassOrProtocolName,
        descriptor: ClassDescriptor,
        superProtocols: List<String>,
        members: List<Stub<*>>,
        attributes: List<String> = emptyList()
): ObjCProtocol = ObjCProtocol(
        name.objCName,
        descriptor,
        superProtocols,
        members,
        attributes + name.toNameAttributes()
)

private fun ObjCExportNamer.ClassOrProtocolName.toNameAttributes(): List<String> = listOfNotNull(
        binaryName.takeIf { it != objCName }?.let { objcRuntimeNameAttribute(it) },
        swiftName.takeIf { it != objCName }?.let { swiftNameAttribute(it) }
)

private fun swiftNameAttribute(swiftName: String) = "swift_name(\"$swiftName\")"
private fun objcRuntimeNameAttribute(name: String) = "objc_runtime_name(\"$name\")"
