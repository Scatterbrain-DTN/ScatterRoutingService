package net.ballmerlabs.sbproto

import com.google.devtools.ksp.containingFile
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSVisitorVoid
import com.google.devtools.ksp.validate
import com.google.protobuf.CodedInputStream
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.asTypeName
import com.squareup.kotlinpoet.ksp.addOriginatingKSFile
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo
import net.ballmerlabs.scatterproto.InvalidPacketException
import net.ballmerlabs.scatterproto.MessageSizeException
import net.ballmerlabs.scatterproto.ScatterSerializable
import proto.Scatterbrain
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

data class ParserElement(
    val parserClass: ClassName,
    val parserType: String,
)

class SbProcessor(
    private val options: Map<String, String>,
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {


    private val parsers: MutableMap<String, ParserElement> = mutableMapOf()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation(SbPacket::class.qualifiedName!!)
        val parserSymbols = resolver.getSymbolsWithAnnotation(SbParser::class.qualifiedName!!)
        val unableToProcess = symbols.filterNot { s -> s.validate() }
        val parserUnableToProcess = symbols.filterNot { s -> s.validate() }

        symbols.filter { p -> p is KSClassDeclaration && p.validate()  }
            .forEach { p ->
                p.accept(
                    ParserVisitor(
                        p.annotations.find { v ->
                            v.annotationType.resolve()
                                .toClassName().simpleName == SbPacket::class.simpleName
                        }!!
                    ),
                    Unit
                )
            }


        parserSymbols.filter { p -> p.validate()  }
            .forEach { p ->
                p.accept(CombinedVisitor(), Unit)
            }



        return unableToProcess.toList() + parserUnableToProcess.toList()
    }

    inner class CombinedVisitor : KSVisitorVoid() {
        private lateinit var packageName: String

        override fun visitFile(file: KSFile, data: Unit) {
            packageName = file.packageName.asString()

            val fileName = "Parsers"

            val funSpecBuilder = FunSpec.builder("parseTypePrefix")
                .addOriginatingKSFile(file)
                .addParameter(ParameterSpec("inputStream", InputStream::class.asTypeName()))
                .addStatement(
                    "         val crc = ByteArray(Int.SIZE_BYTES)\n" +
                            "         val size = ByteArray(Int.SIZE_BYTES)\n" +
                            "         val typesize = ByteArray(Int.SIZE_BYTES)\n" +
                            "         if (inputStream.read(typesize) != Int.SIZE_BYTES) {\n" +
                            "              throw IOException(\"end of stream\")\n" +
                            "          }\n" +
                            "          if (inputStream.read(size) != Int.SIZE_BYTES) {\n" +
                            "              throw IOException(\"end of stream\")\n" +
                            "          }\n" +
                            "\n" +
                            "          val s = ByteBuffer.wrap(size).order(ByteOrder.BIG_ENDIAN).int\n" +
                            "          val s2 = ByteBuffer.wrap(typesize).order(ByteOrder.BIG_ENDIAN).int\n" +
                            "          if (s > BLOCK_SIZE_CAP) {\n" +
                            "              throw MessageSizeException()\n" +
                            "          }\n" +
                            "          if (s2 > BLOCK_SIZE_CAP) {\n" +
                            "              throw MessageSizeException()\n" +
                            "          }\n" +
                            "          val co = CodedInputStream.newInstance(inputStream, s + 1)\n" +
                            "          val typeBytes = co.readRawBytes(s2)\n" +
                            "          val type = Scatterbrain.TypePrefix.parseFrom(typeBytes)\n" +
                            "\n" +
                            "          val messageBytes = co.readRawBytes(s)\n" +
                            "          val parser = when (type.type) {"
                )
                .returns(ScatterSerializable.Companion.TypedPacket::class)

            parsers.forEach { (k, v) ->
                funSpecBuilder.addStatement("%L -> %L.parser", v.parserType, v.parserClass)
            }

            funSpecBuilder.addStatement(
                "    else -> throw InvalidPacketException(type.type, expected = Scatterbrain.MessageType.INVALID)\n" +
                        "}\n" +
                        "val message = parser.parser.parseFrom(messageBytes)\n" +
                        "                if (inputStream.read(crc) != crc.size) {\n" +
                        "                    throw IOException(\"end of stream\")\n" +
                        "                }\n" +
                        "                val crc32 = CRC32()\n" +
                        "                crc32.update(typesize)\n" +
                        "                crc32.update(size)\n" +
                        "                crc32.update(typeBytes)\n" +
                        "                crc32.update(messageBytes)\n" +
                        "                if (crc32.value != bytes2long(crc)) {\n" +
                        "                    throw IOException(\"invalid crc: \" + crc32.value + \" \" + bytes2long(crc))\n" +
                        "                }\n" +
                        "                return ScatterSerializable.Companion.TypedPacket(\n" +
                        "                    type = type.type,\n" +
                        "                    packet = message\n" +
                        "                )"
            )

            val fileSpec = FileSpec.builder(
                packageName = packageName,
                fileName = fileName
            ).apply {
                addImport(
                    IOException::class.asClassName().packageName,
                    IOException::class.asClassName().simpleName
                )
                addImport(
                    ByteBuffer::class.asClassName().packageName,
                    ByteBuffer::class.asClassName().simpleName
                )
                addImport("net.ballmerlabs.scatterproto", "BLOCK_SIZE_CAP")
                addImport(
                    MessageSizeException::class.asClassName().packageName,
                    MessageSizeException::class.asClassName().simpleName
                )
                addImport(
                    InvalidPacketException::class.asClassName().packageName,
                    InvalidPacketException::class.asClassName().simpleName
                )
                addImport(
                    ByteOrder::class.asClassName().packageName,
                    ByteOrder::class.asClassName().simpleName
                )
                addImport(
                    CodedInputStream::class.asClassName().packageName,
                    CodedInputStream::class.asClassName().simpleName
                )
                addImport(
                    Scatterbrain::class.asClassName().packageName,
                    Scatterbrain::class.asClassName().simpleName
                )
                addImport(
                    CRC32::class.asClassName().packageName,
                    CRC32::class.asClassName().simpleName
                )

                addImport("net.ballmerlabs.scatterproto", "bytes2long")
                addFunction(funSpecBuilder.build())
            }.build()

            fileSpec.writeTo(codeGenerator = codeGenerator, aggregating = true)
        }
        override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
            packageName = classDeclaration.packageName.asString()
            classDeclaration.qualifiedName?.asString() ?: run {
                logger.error(
                    "@SbPacket must target classes with qualified names",
                    classDeclaration
                )
                return
            }
        }
    }

    inner class ParserVisitor(val annotation: KSAnnotation) : KSVisitorVoid() {
        private lateinit var packageName: String
        private lateinit var ksType: KSType

        override fun visitClassDeclaration(
            classDeclaration: KSClassDeclaration,
            data: Unit,
        ) {
            packageName = classDeclaration.packageName.asString()
            ksType = classDeclaration.asType(emptyList())


            classDeclaration.qualifiedName?.asString() ?: run {
                logger.error(
                    "@SbPacket must target classes with qualified names",
                    classDeclaration
                )
                return
            }

            val type =
                annotation.arguments.find { v -> v.name?.asString() == "messageType" } ?: run {
                    logger.error(
                        "@SbPacket missing argument messageType",
                        classDeclaration
                    )
                    return
                }

            val scatterType = classDeclaration.superTypes.find { v ->
                v.resolve().toClassName().simpleName == ScatterSerializable::class.simpleName
            } ?: run {
                logger.error("the class should extend ScatterSerializable")
                return
            }

            val scatterTv = scatterType.resolve().arguments.firstOrNull() ?: run {
                logger.error("superclass doesn't have type parameter")
                return
            }

            val fileName = classDeclaration.simpleName.asString() + "Parser"

            val qualifiedName = ClassName.bestGuess("$packageName.$fileName")

            val packetType = type.value?.toString()!!

            parsers[fileName] = ParserElement(
                parserClass = qualifiedName,
                parserType = packetType
            )

            val fileSpec = FileSpec.builder(
                packageName = packageName,
                fileName = fileName
            ).apply {

                addType(TypeSpec.classBuilder(fileName)
                    .addOriginatingKSFile(classDeclaration.containingFile!!)
                    .apply {
                    superclass(
                        ScatterSerializable.Companion.Parser::class.asClassName().parameterizedBy(
                            scatterTv.toTypeName(),
                            classDeclaration.toClassName()
                        )
                    ).addOriginatingKSFile(classDeclaration.containingFile!!)
                        .apply {
                        addSuperclassConstructorParameter(
                            "%L.parser(), %L",
                            scatterTv.toTypeName(),
                            packetType
                        ).addOriginatingKSFile(classDeclaration.containingFile!!)

                    }

                    addType(TypeSpec.companionObjectBuilder()
                        .addOriginatingKSFile(classDeclaration.containingFile!!)
                        .apply {
                        addProperty(PropertySpec.builder("parser", qualifiedName)
                            .addOriginatingKSFile(classDeclaration.containingFile!!)
                            .apply {
                                initializer(CodeBlock.of("%L()", qualifiedName))
                            }
                            .build())
                    }.build())
                }.build())


            }.build()
            fileSpec.writeTo(codeGenerator = codeGenerator, aggregating = true)
        }
    }
}