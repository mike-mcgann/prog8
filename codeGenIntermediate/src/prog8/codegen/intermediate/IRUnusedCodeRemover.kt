package prog8.codegen.intermediate

import prog8.code.core.IErrorReporter
import prog8.code.core.SourceCode.Companion.libraryFilePrefix
import prog8.intermediate.*


internal class IRUnusedCodeRemover(private val irprog: IRProgram, private val errors: IErrorReporter) {
    fun optimize(): Int {
        val allLabeledChunks = mutableMapOf<String, IRCodeChunkBase>()

        irprog.blocks.asSequence().flatMap { it.children.filterIsInstance<IRSubroutine>() }.forEach { sub ->
            sub.chunks.forEach { chunk ->
                chunk.label?.let { allLabeledChunks[it] = chunk }
            }
        }

        var numRemoved = removeSimpleUnlinked(allLabeledChunks) + removeUnreachable(allLabeledChunks)

        // remove empty subs
        irprog.blocks.forEach { block ->
            block.children.filterIsInstance<IRSubroutine>().reversed().forEach { sub ->
                if(sub.isEmpty()) {
                    if(!sub.position.file.startsWith(libraryFilePrefix))
                        errors.warn("unused subroutine ${sub.label}", sub.position)
                    block.children.remove(sub)
                    numRemoved++
                }
            }
        }

        // remove empty blocks
        irprog.blocks.reversed().forEach { block ->
            if(block.isEmpty()) {
                irprog.blocks.remove(block)
                numRemoved++
            }
        }

        return numRemoved
    }

    private fun removeUnreachable(allLabeledChunks: MutableMap<String, IRCodeChunkBase>): Int {
        val entrypointSub = irprog.blocks.single { it.name=="main" }.children.single { it is IRSubroutine && it.label=="main.start" }
        val reachable = mutableSetOf((entrypointSub as IRSubroutine).chunks.first())

        fun grow() {
            val new = mutableSetOf<IRCodeChunkBase>()
            reachable.forEach {
                it.next?.let { next -> new += next }
                it.instructions.forEach { instr ->
                    if (instr.branchTarget == null)
                        instr.labelSymbol?.let { label -> allLabeledChunks[label]?.let { chunk -> new += chunk } }
                    else
                        new += instr.branchTarget!!
                }
            }
            reachable += new
        }

        var previousCount = reachable.size
        while(true) {
            grow()
            if(reachable.size<=previousCount)
                break
            previousCount = reachable.size
        }

        return removeUnlinkedChunks(reachable)
    }

    private fun removeSimpleUnlinked(allLabeledChunks: Map<String, IRCodeChunkBase>): Int {
        val linkedChunks = mutableSetOf<IRCodeChunkBase>()

        irprog.blocks.asSequence().flatMap { it.children.filterIsInstance<IRSubroutine>() }.forEach { sub ->
            sub.chunks.forEach { chunk ->
                chunk.next?.let { next -> linkedChunks += next }
                chunk.instructions.forEach {
                    if(it.branchTarget==null) {
                        it.labelSymbol?.let { label -> allLabeledChunks[label]?.let { cc -> linkedChunks += cc } }
                    } else {
                        linkedChunks += it.branchTarget!!
                    }
                }
                if (chunk.label == "main.start")
                    linkedChunks += chunk
            }
        }

        return removeUnlinkedChunks(linkedChunks)
    }

    private fun removeUnlinkedChunks(
        linkedChunks: MutableSet<IRCodeChunkBase>
    ): Int {
        var numRemoved = 0
        irprog.blocks.asSequence().flatMap { it.children.filterIsInstance<IRSubroutine>() }.forEach { sub ->
            sub.chunks.withIndex().reversed().forEach { (index, chunk) ->
                if (chunk !in linkedChunks) {
                    if (chunk === sub.chunks[0]) {
                        when(chunk) {
                            is IRCodeChunk -> {
                                if (chunk.isNotEmpty()) {
                                    // don't remove the first chunk of the sub itself because it has to have the name of the sub as label
                                    chunk.instructions.clear()
                                    numRemoved++
                                }
                            }
                            is IRInlineAsmChunk, is IRInlineBinaryChunk -> {
                                sub.chunks[index] = IRCodeChunk(chunk.label, chunk.next)
                                numRemoved++
                            }
                        }
                    } else {
                        sub.chunks.removeAt(index)
                        numRemoved++
                    }
                }
            }
        }
        return numRemoved
    }
}