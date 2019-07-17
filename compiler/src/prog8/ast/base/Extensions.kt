package prog8.ast.base

import prog8.ast.Module
import prog8.ast.Program
import prog8.ast.processing.*
import prog8.compiler.CompilationOptions
import prog8.optimizer.FlattenAnonymousScopesAndRemoveNops


// the name of the subroutine that should be called for every block to initialize its variables
internal const val initvarsSubName="prog8_init_vars"


// prefix for literal values that are turned into a variable on the heap
internal const val autoHeapValuePrefix = "auto_heap_value_"


internal fun Program.removeNopsFlattenAnonScopes() {
    val flattener = FlattenAnonymousScopesAndRemoveNops()
    flattener.visit(this)
}


internal fun Program.checkValid(compilerOptions: CompilationOptions) {
    val checker = AstChecker(this, compilerOptions)
    checker.visit(this)
    printErrors(checker.result(), name)
}


internal fun Program.reorderStatements() {
    val initvalueCreator = VarInitValueAndAddressOfCreator(namespace, heap)
    initvalueCreator.visit(this)

    val checker = StatementReorderer(this)
    checker.visit(this)
}

internal fun Module.checkImportedValid() {
    val checker = ImportedModuleDirectiveRemover()
    checker.visit(this)
    printErrors(checker.result(), name)
}

internal fun Program.checkRecursion() {
    val checker = AstRecursionChecker(namespace)
    checker.visit(this)
    printErrors(checker.result(), name)
}


internal fun Program.checkIdentifiers() {
    val checker = AstIdentifiersChecker(this)
    checker.visit(this)

    if(modules.map {it.name}.toSet().size != modules.size) {
        throw FatalAstException("modules should all be unique")
    }

    printErrors(checker.result(), name)
}
