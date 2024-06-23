package net.ballmerlabs.sbproto

import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider

class SbProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return SbProcessor(
            options = environment.options,
            codeGenerator = environment.codeGenerator,
            logger = environment.logger
        )
    }
}