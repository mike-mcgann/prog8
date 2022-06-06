package prog8.compiler.astprocessing

import prog8.ast.Node
import prog8.ast.Program
import prog8.ast.expressions.ArrayIndexedExpression
import prog8.ast.expressions.BinaryExpression
import prog8.ast.expressions.Expression
import prog8.ast.statements.AssignTarget
import prog8.ast.statements.DirectMemoryWrite
import prog8.ast.statements.VarDecl
import prog8.ast.walk.AstWalker
import prog8.ast.walk.IAstModification
import prog8.code.core.CompilationOptions
import prog8.code.core.DataType
import prog8.code.target.VMTarget


internal class AstOnetimeTransforms(private val program: Program, private val options: CompilationOptions) : AstWalker() {

    override fun after(arrayIndexedExpression: ArrayIndexedExpression, parent: Node): Iterable<IAstModification> {
        if(parent !is VarDecl) {
            // TODO move this / remove this, and make the codegen better instead.
            // If the expression is pointervar[idx] where pointervar is uword and not a real array,
            // replace it by a @(pointervar+idx) expression.
            // Don't replace the initializer value in a vardecl - this will be moved to a separate
            // assignment statement soon in after(VarDecl)
            return replacePointerVarIndexWithMemreadOrMemwrite(arrayIndexedExpression, parent)
        }
        return noModifications
    }

    private fun replacePointerVarIndexWithMemreadOrMemwrite(arrayIndexedExpression: ArrayIndexedExpression, parent: Node): Iterable<IAstModification> {
        if(options.compTarget.name== VMTarget.NAME)
            return noModifications

        val arrayVar = arrayIndexedExpression.arrayvar.targetVarDecl(program)
        if(arrayVar!=null && arrayVar.datatype == DataType.UWORD) {
            if(parent is AssignTarget) {
                // rewrite assignment target   pointervar[index]  into  @(pointervar+index)
                //println("REWRITE POINTER INDEXED: ${arrayVar.name}[${arrayIndexedExpression.indexer.indexExpr}] as assignment target at ${parent.position}") // TODO
                val indexer = arrayIndexedExpression.indexer
                val add: Expression =
                if(indexer.indexExpr.constValue(program)?.number==0.0)
                    arrayIndexedExpression.arrayvar.copy()
                else
                    BinaryExpression(arrayIndexedExpression.arrayvar.copy(), "+", indexer.indexExpr, arrayIndexedExpression.position)
                val memwrite = DirectMemoryWrite(add, arrayIndexedExpression.position)
                val newtarget = AssignTarget(null, null, memwrite, arrayIndexedExpression.position)
                return listOf(IAstModification.ReplaceNode(parent, newtarget, parent.parent))
            }
        }

        return noModifications
    }
}

