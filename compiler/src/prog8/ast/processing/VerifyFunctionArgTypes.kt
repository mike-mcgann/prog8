package prog8.ast.processing

import prog8.ast.IFunctionCall
import prog8.ast.INameScope
import prog8.ast.Program
import prog8.ast.base.DataType
import prog8.ast.base.FatalAstException
import prog8.ast.expressions.Expression
import prog8.ast.expressions.FunctionCall
import prog8.ast.statements.*
import prog8.compiler.CompilerException
import prog8.functions.BuiltinFunctions

class VerifyFunctionArgTypes(val program: Program) : IAstVisitor {

    override fun visit(functionCall: FunctionCall) {
        val error = checkTypes(functionCall as IFunctionCall, functionCall.definingScope(), program)
        if(error!=null)
            throw CompilerException(error)
    }

    override fun visit(functionCallStatement: FunctionCallStatement) {
        val error = checkTypes(functionCallStatement as IFunctionCall, functionCallStatement.definingScope(), program)
        if (error!=null)
            throw CompilerException(error)
    }

    companion object {

        private fun argTypeCompatible(argDt: DataType, paramDt: DataType): Boolean {
            if(argDt==paramDt)
                return true

            // there are some exceptions that are considered compatible, such as STR <> UWORD
            if(argDt==DataType.STR && paramDt==DataType.UWORD ||
                    argDt==DataType.UWORD && paramDt==DataType.STR)
                return true

            return false
        }

        fun checkTypes(call: IFunctionCall, scope: INameScope, program: Program): String? {
            val argITypes = call.args.map { it.inferType(program) }
            val firstUnknownDt = argITypes.indexOfFirst { it.isUnknown }
            if(firstUnknownDt>=0)
                return "argument ${firstUnknownDt+1} invalid argument type"
            val argtypes = argITypes.map { it.typeOrElse(DataType.STRUCT) }
            val target = call.target.targetStatement(scope)
            if (target is Subroutine) {
                if(call.args.size != target.parameters.size)
                    return "invalid number of arguments"
                val paramtypes = target.parameters.map { it.type }
                val mismatch = argtypes.zip(paramtypes).indexOfFirst { !argTypeCompatible(it.first, it.second) }
                if(mismatch>=0) {
                    val actual = argtypes[mismatch].toString()
                    val expected = paramtypes[mismatch].toString()
                    return "argument ${mismatch + 1} type mismatch, was: $actual expected: $expected"
                }
                if(target.isAsmSubroutine) {
                    if(target.asmReturnvaluesRegisters.size>1) {
                        // multiple return values will NOT work inside an expression.
                        // they MIGHT work in a regular assignment or just a function call statement.
                        val parent = if(call is Statement) call.parent else if(call is Expression) call.parent else null
                        if(call !is FunctionCallStatement && parent !is Assignment && parent !is VarDecl) {
                            return "can't use subroutine call that returns multiple return values here (try moving it into a separate assignment)"
                        }
                    }
                }
            }
            else if (target is BuiltinFunctionStatementPlaceholder) {
                val func = BuiltinFunctions.getValue(target.name)
                if(call.args.size != func.parameters.size)
                    return "invalid number of arguments"
                val paramtypes = func.parameters.map { it.possibleDatatypes }
                argtypes.zip(paramtypes).forEachIndexed { index, pair ->
                    val anyCompatible = pair.second.any { argTypeCompatible(pair.first, it) }
                    if (!anyCompatible) {
                        val actual = pair.first.toString()
                        val expected = pair.second.toString()
                        return "argument ${index + 1} type mismatch, was: $actual expected: $expected"
                    }
                }
            }

            return null
        }
    }
}
