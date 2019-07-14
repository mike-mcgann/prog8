package prog8.ast.processing

import kotlin.comparisons.nullsLast
import prog8.ast.*
import prog8.ast.base.*
import prog8.ast.base.initvarsSubName
import prog8.ast.expressions.*
import prog8.ast.statements.*
import prog8.functions.BuiltinFunctions


fun flattenStructAssignment(structAssignment: Assignment, program: Program): List<Assignment> {
    val identifier = structAssignment.target.identifier!!
    val identifierName = identifier.nameInSource.single()
    val targetVar = identifier.targetVarDecl(program.namespace)!!
    val struct = targetVar.struct!!
    val sourceVar = (structAssignment.value as IdentifierReference).targetVarDecl(program.namespace)!!
    if(!sourceVar.isArray && sourceVar.struct==null)
        throw FatalAstException("can only assign arrays or structs to structs")
    if(sourceVar.isArray) {
        val sourceArray = (sourceVar.value as ReferenceLiteralValue).array!!
        return struct.statements.zip(sourceArray).map { member ->
            val decl = member.first as VarDecl
            val mangled = mangledStructMemberName(identifierName, decl.name)
            val idref = IdentifierReference(listOf(mangled), structAssignment.position)
            val assign = Assignment(AssignTarget(null, idref, null, null, structAssignment.position),
                    null, member.second, member.second.position)
            assign.linkParents(structAssignment)
            assign
        }
    }
    else {
        // struct memberwise copy
        val sourceStruct = sourceVar.struct!!
        if(sourceStruct!==targetVar.struct) {
            // structs are not the same in assignment
            return listOf()     // error will be printed elsewhere
        }
        return struct.statements.zip(sourceStruct.statements).map { member ->
            val targetDecl = member.first as VarDecl
            val sourceDecl = member.second as VarDecl
            if(targetDecl.name != sourceDecl.name)
                throw FatalAstException("struct member mismatch")
            val mangled = mangledStructMemberName(identifierName, targetDecl.name)
            val idref = IdentifierReference(listOf(mangled), structAssignment.position)
            val sourcemangled = mangledStructMemberName(sourceVar.name, sourceDecl.name)
            val sourceIdref = IdentifierReference(listOf(sourcemangled), structAssignment.position)
            val assign = Assignment(AssignTarget(null, idref, null, null, structAssignment.position),
                    null, sourceIdref, member.second.position)
            assign.linkParents(structAssignment)
            assign
        }
    }
}


internal class StatementReorderer(private val program: Program): IAstModifyingVisitor {
    // Reorders the statements in a way the compiler needs.
    // - 'main' block must be the very first statement UNLESS it has an address set.
    // - blocks are ordered by address, where blocks without address are put at the end.
    // - in every scope:
    //      -- the directives '%output', '%launcher', '%zeropage', '%zpreserved', '%address' and '%option' will come first.
    //      -- all vardecls then follow.
    //      -- the remaining statements then follow in their original order.
    //
    // - the 'start' subroutine in the 'main' block will be moved to the top immediately following the directives.
    // - all other subroutines will be moved to the end of their block.
    // - sorts the choices in when statement.
    //
    // Also, makes sure any value assignments get the proper type casts if needed to cast them into the target variable's type.
    // (this includes function call arguments)

    private val directivesToMove = setOf("%output", "%launcher", "%zeropage", "%zpreserved", "%address", "%option")

    override fun visit(module: Module) {
        super.visit(module)

        val (blocks, other) = module.statements.partition { it is Block }
        module.statements = other.asSequence().plus(blocks.sortedBy { (it as Block).address ?: Int.MAX_VALUE }).toMutableList()

        // make sure user-defined blocks come BEFORE library blocks, and move the "main" block to the top of everything
        val nonLibraryBlocks = module.statements.withIndex()
                .filter { it.value is Block && !(it.value as Block).isInLibrary }
                .map { it.index to it.value }
                .reversed()
        for(nonLibBlock in nonLibraryBlocks)
            module.statements.removeAt(nonLibBlock.first)
        for(nonLibBlock in nonLibraryBlocks)
            module.statements.add(0, nonLibBlock.second)
        val mainBlock = module.statements.singleOrNull { it is Block && it.name=="main" }
        if(mainBlock!=null && (mainBlock as Block).address==null) {
            module.remove(mainBlock)
            module.statements.add(0, mainBlock)
        }

        val varDecls = module.statements.filterIsInstance<VarDecl>()
        module.statements.removeAll(varDecls)
        module.statements.addAll(0, varDecls)

        val directives = module.statements.filter {it is Directive && it.directive in directivesToMove}
        module.statements.removeAll(directives)
        module.statements.addAll(0, directives)
    }

    override fun visit(block: Block): IStatement {

        val subroutines = block.statements.filterIsInstance<Subroutine>()
        var numSubroutinesAtEnd = 0
        // move all subroutines to the end of the block
        for (subroutine in subroutines) {
            if(subroutine.name!="start" || block.name!="main") {
                block.remove(subroutine)
                block.statements.add(subroutine)
            }
            numSubroutinesAtEnd++
        }
        // move the "start" subroutine to the top
        if(block.name=="main") {
            block.statements.singleOrNull { it is Subroutine && it.name == "start" } ?.let {
                block.remove(it)
                block.statements.add(0, it)
                numSubroutinesAtEnd--
            }
        }

        // make sure there is a 'return' in front of the first subroutine
        // (if it isn't the first statement in the block itself, and isn't the program's entrypoint)
        if(numSubroutinesAtEnd>0 && block.statements.size > (numSubroutinesAtEnd+1)) {
            val firstSub = block.statements[block.statements.size - numSubroutinesAtEnd] as Subroutine
            if(firstSub.name != "start" && block.name != "main") {
                val stmtBeforeFirstSub = block.statements[block.statements.size - numSubroutinesAtEnd - 1]
                if (stmtBeforeFirstSub !is Return
                        && stmtBeforeFirstSub !is Jump
                        && stmtBeforeFirstSub !is Subroutine
                        && stmtBeforeFirstSub !is BuiltinFunctionStatementPlaceholder) {
                    val ret = Return(null, stmtBeforeFirstSub.position)
                    ret.linkParents(block)
                    block.statements.add(block.statements.size - numSubroutinesAtEnd, ret)
                }
            }
        }

        val varDecls = block.statements.filterIsInstance<VarDecl>()
        block.statements.removeAll(varDecls)
        block.statements.addAll(0, varDecls)
        val directives = block.statements.filter {it is Directive && it.directive in directivesToMove}
        block.statements.removeAll(directives)
        block.statements.addAll(0, directives)
        block.linkParents(block.parent)

        // create subroutine that initializes the block's variables (if any)
        val varInits = block.statements.withIndex().filter { it.value is VariableInitializationAssignment }
        if(varInits.isNotEmpty()) {
            val statements = varInits.map{it.value}.toMutableList()
            val varInitSub = Subroutine(initvarsSubName, emptyList(), emptyList(), emptyList(), emptyList(),
                    emptySet(), null, false, statements, block.position)
            varInitSub.keepAlways = true
            varInitSub.linkParents(block)
            block.statements.add(varInitSub)

            // remove the varinits from the block's statements
            for(index in varInits.map{it.index}.reversed())
                block.statements.removeAt(index)
        }

        return super.visit(block)
    }

    override fun visit(subroutine: Subroutine): IStatement {
        super.visit(subroutine)

        val varDecls = subroutine.statements.filterIsInstance<VarDecl>()
        subroutine.statements.removeAll(varDecls)
        subroutine.statements.addAll(0, varDecls)
        val directives = subroutine.statements.filter {it is Directive && it.directive in directivesToMove}
        subroutine.statements.removeAll(directives)
        subroutine.statements.addAll(0, directives)

        if(subroutine.returntypes.isEmpty()) {
            // add the implicit return statement at the end (if it's not there yet), but only if it's not a kernel routine.
            // and if an assembly block doesn't contain a rts/rti
            if(subroutine.asmAddress==null && subroutine.amountOfRtsInAsm()==0) {
                if (subroutine.statements.lastOrNull {it !is VarDecl } !is Return) {
                    val returnStmt = Return(null, subroutine.position)
                    returnStmt.linkParents(subroutine)
                    subroutine.statements.add(returnStmt)
                }
            }
        }

        return subroutine
    }

    override fun visit(expr: BinaryExpression): IExpression {
        val leftDt = expr.left.inferType(program)
        val rightDt = expr.right.inferType(program)
        if(leftDt!=null && rightDt!=null && leftDt!=rightDt) {
            // determine common datatype and add typecast as required to make left and right equal types
            val (commonDt, toFix) = expr.commonDatatype(leftDt, rightDt, expr.left, expr.right)
            if(toFix!=null) {
                when {
                    toFix===expr.left -> {
                        expr.left = TypecastExpression(expr.left, commonDt, true, expr.left.position)
                        expr.left.linkParents(expr)
                    }
                    toFix===expr.right -> {
                        expr.right = TypecastExpression(expr.right, commonDt, true, expr.right.position)
                        expr.right.linkParents(expr)
                    }
                    else -> throw FatalAstException("confused binary expression side")
                }
            }
        }
        return super.visit(expr)
    }

    override fun visit(assignment: Assignment): IStatement {
        // see if a typecast is needed to convert the value's type into the proper target type
        val valuetype = assignment.value.inferType(program)
        val targettype = assignment.target.inferType(program, assignment)
        if(targettype!=null && valuetype!=null) {
            if(valuetype!=targettype) {
                if (valuetype isAssignableTo targettype) {
                    assignment.value = TypecastExpression(assignment.value, targettype, true, assignment.value.position)
                    assignment.value.linkParents(assignment)
                }
                // if they're not assignable, we'll get a proper error later from the AstChecker
            }
        }

        // struct assignments will be flattened
        if(valuetype==DataType.STRUCT && targettype==DataType.STRUCT) {
            val assignments = flattenStructAssignment(assignment, program)
            if(assignments.isEmpty()) {
                // something went wrong (probably incompatible struct types)
                // we'll get an error later from the AstChecker
                return assignment
            } else {
                val scope = AnonymousScope(assignments.toMutableList(), assignment.position)
                scope.linkParents(assignment.parent)
                return scope
            }
        }

        return super.visit(assignment)
    }

    override fun visit(functionCallStatement: FunctionCallStatement): IStatement {
        checkFunctionCallArguments(functionCallStatement, functionCallStatement.definingScope())
        return super.visit(functionCallStatement)
    }

    override fun visit(functionCall: FunctionCall): IExpression {
        checkFunctionCallArguments(functionCall, functionCall.definingScope())
        return super.visit(functionCall)
    }

    private fun checkFunctionCallArguments(call: IFunctionCall, scope: INameScope) {
        // see if a typecast is needed to convert the arguments into the required parameter's type
        when(val sub = call.target.targetStatement(scope)) {
            is Subroutine -> {
                for(arg in sub.parameters.zip(call.arglist.withIndex())) {
                    val argtype = arg.second.value.inferType(program)
                    if(argtype!=null) {
                        val requiredType = arg.first.type
                        if (requiredType != argtype) {
                            if (argtype isAssignableTo requiredType) {
                                val typecasted = TypecastExpression(arg.second.value, requiredType, true, arg.second.value.position)
                                typecasted.linkParents(arg.second.value.parent)
                                call.arglist[arg.second.index] = typecasted
                            }
                            // if they're not assignable, we'll get a proper error later from the AstChecker
                        }
                    }
                }
            }
            is BuiltinFunctionStatementPlaceholder -> {
                // if(sub.name in setOf("lsl", "lsr", "rol", "ror", "rol2", "ror2", "memset", "memcopy", "memsetw", "swap"))
                val func = BuiltinFunctions.getValue(sub.name)
                if(func.pure) {
                    // non-pure functions don't get automatic typecasts because sometimes they act directly on their parameters
                    for (arg in func.parameters.zip(call.arglist.withIndex())) {
                        val argtype = arg.second.value.inferType(program)
                        if (argtype != null) {
                            if (arg.first.possibleDatatypes.any { argtype == it })
                                continue
                            for (possibleType in arg.first.possibleDatatypes) {
                                if (argtype isAssignableTo possibleType) {
                                    val typecasted = TypecastExpression(arg.second.value, possibleType, true, arg.second.value.position)
                                    typecasted.linkParents(arg.second.value.parent)
                                    call.arglist[arg.second.index] = typecasted
                                    break
                                }
                            }
                        }
                    }
                }
            }
            null -> {}
            else -> TODO("call to something weird $sub   ${call.target}")
        }
    }

    private fun sortConstantAssignmentSequence(first: Assignment, stmtIter: MutableIterator<IStatement>): Pair<List<Assignment>, IStatement?> {
        val sequence= mutableListOf(first)
        var trailing: IStatement? = null
        while(stmtIter.hasNext()) {
            val next = stmtIter.next()
            if(next is Assignment) {
                val constValue = next.value.constValue(program)
                if(constValue==null) {
                    trailing = next
                    break
                }
                sequence.add(next)
            }
            else {
                trailing=next
                break
            }
        }
        val sorted = sequence.sortedWith(compareBy({it.value.inferType(program)}, {it.target.shortString(true)}))
        return Pair(sorted, trailing)
    }

    override fun visit(typecast: TypecastExpression): IExpression {
        // warn about any implicit type casts to Float, because that may not be intended
        if(typecast.implicit && typecast.type in setOf(DataType.FLOAT, DataType.ARRAY_F)) {
            printWarning("byte or word value implicitly converted to float. Suggestion: use explicit cast as float, a float number, or revert to integer arithmetic", typecast.position)
        }
        return super.visit(typecast)
    }

    override fun visit(whenStatement: WhenStatement): IStatement {
        // make sure all choices are just for one single value
        val choices = whenStatement.choices.toList()
        for(choice in choices) {
            if(choice.values==null || choice.values.size==1)
                continue
            for(v in choice.values) {
                val newchoice=WhenChoice(listOf(v), choice.statements, choice.position)
                newchoice.parent = choice.parent
                whenStatement.choices.add(newchoice)
            }
            whenStatement.choices.remove(choice)
        }

        // sort the choices in low-to-high value order (nulls last)
        whenStatement.choices
                .sortWith(compareBy<WhenChoice, Int?>(nullsLast(), {it.values?.single()?.constValue(program)?.number?.toInt()}))
        return super.visit(whenStatement)
    }

    override fun visit(memread: DirectMemoryRead): IExpression {
        // make sure the memory address is an uword
        val dt = memread.addressExpression.inferType(program)
        if(dt!=DataType.UWORD) {
            val literaladdr = memread.addressExpression as? NumericLiteralValue
            if(literaladdr!=null) {
                memread.addressExpression = literaladdr.cast(DataType.UWORD)!!
            } else {
                memread.addressExpression = TypecastExpression(memread.addressExpression, DataType.UWORD, true, memread.addressExpression.position)
                memread.addressExpression.parent = memread
            }
        }
        return super.visit(memread)
    }

    override fun visit(memwrite: DirectMemoryWrite) {
        val dt = memwrite.addressExpression.inferType(program)
        if(dt!=DataType.UWORD) {
            val literaladdr = memwrite.addressExpression as? NumericLiteralValue
            if(literaladdr!=null) {
                memwrite.addressExpression = literaladdr.cast(DataType.UWORD)!!
            } else {
                memwrite.addressExpression = TypecastExpression(memwrite.addressExpression, DataType.UWORD, true, memwrite.addressExpression.position)
                memwrite.addressExpression.parent = memwrite
            }
        }
        super.visit(memwrite)
    }
}
