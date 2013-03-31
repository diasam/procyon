/*
 * DeclareVariablesTransform.java
 *
 * Copyright (c) 2013 Mike Strobel
 *
 * This source code is subject to terms and conditions of the Apache License, Version 2.0.
 * A copy of the license can be found in the License.html file at the root of this distribution.
 * By using this source code in any fashion, you are agreeing to be bound by the terms of the
 * Apache License, Version 2.0.
 *
 * You must not remove this notice, or any other, from this software.
 */

package com.strobel.decompiler.languages.java.ast.transforms;

import com.strobel.core.StringUtilities;
import com.strobel.core.StrongBox;
import com.strobel.core.VerifyArgument;
import com.strobel.decompiler.DecompilerContext;
import com.strobel.decompiler.ast.Variable;
import com.strobel.decompiler.languages.java.ast.*;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("ProtectedField")
public class DeclareVariablesTransform implements IAstTransform {
    protected final List<VariableToDeclare> variablesToDeclare = new ArrayList<>();
    protected final DecompilerContext context;

    public DeclareVariablesTransform(final DecompilerContext context) {
        this.context = VerifyArgument.notNull(context, "context");
    }

    @Override
    public void run(final AstNode node) {
        run(node, null);

        for (final VariableToDeclare v : variablesToDeclare) {
            final Variable variable = v.getVariable();
            final AssignmentExpression replacedAssignment = v.getReplacedAssignment();

            if (replacedAssignment == null) {
                final BlockStatement block = (BlockStatement) v.getInsertionPoint().getParent();
                final boolean effectivelyFinal = getAssignmentCount(block, v.getName()) == 1;
                final VariableDeclarationStatement declaration = new VariableDeclarationStatement(v.getType().clone(), v.getName());

                if (variable != null) {
                    declaration.getVariables().firstOrNullObject().putUserData(Keys.VARIABLE, variable);
                }

                if (effectivelyFinal) {
                    declaration.addModifier(Modifier.FINAL);
                }

                block.getStatements().insertBefore(v.getInsertionPoint(), declaration);
            }
        }

        //
        // Do all the insertions before the replacements.  This is necessary because a replacement
        // might remove our reference point from the AST.
        //

        for (final VariableToDeclare v : variablesToDeclare) {
            final Variable variable = v.getVariable();
            final AssignmentExpression replacedAssignment = v.getReplacedAssignment();

            if (replacedAssignment != null) {
                final VariableInitializer initializer = new VariableInitializer(v.getName());
                final Expression right = replacedAssignment.getRight();

                right.remove();
                right.putUserData(Keys.MEMBER_REFERENCE, replacedAssignment.getUserData(Keys.MEMBER_REFERENCE));
                right.putUserData(Keys.VARIABLE, variable);

                initializer.setInitializer(right);

                final VariableDeclarationStatement declaration = new VariableDeclarationStatement();

                declaration.setType(v.getType().clone());
                declaration.getVariables().add(initializer);

                final AstNode parent = replacedAssignment.getParent();

                if (parent instanceof ExpressionStatement) {
                    if (getAssignmentCount(parent.getParent(), v.getName()) == 1) {
                        declaration.addModifier(Modifier.FINAL);
                    }

                    declaration.putUserData(Keys.MEMBER_REFERENCE, parent.getUserData(Keys.MEMBER_REFERENCE));
                    declaration.putUserData(Keys.VARIABLE, parent.getUserData(Keys.VARIABLE));
                    parent.replaceWith(declaration);
                }
                else {
                    if (getAssignmentCount(parent, v.getName()) == 1) {
                        declaration.addModifier(Modifier.FINAL);
                    }

                    replacedAssignment.replaceWith(declaration);
                }
            }
        }

        variablesToDeclare.clear();
    }

    private void run(final AstNode node, final DefiniteAssignmentAnalysis daa) {
        DefiniteAssignmentAnalysis analysis = daa;

        if (node instanceof BlockStatement) {
            final BlockStatement block = (BlockStatement) node;
            final List<VariableDeclarationStatement> variables = new ArrayList<>();

            for (final Statement statement : block.getStatements()) {
                if (statement instanceof VariableDeclarationStatement) {
                    variables.add((VariableDeclarationStatement) statement);
                }
            }

            if (!variables.isEmpty()) {
                //
                // Remove old variable declarations.
                //
                for (final VariableDeclarationStatement declaration : variables) {
                    assert declaration.getVariables().size() == 1 &&
                           declaration.getVariables().firstOrNullObject().getInitializer().isNull();

                    declaration.remove();
                }
            }

            if (analysis == null) {
                analysis = new DefiniteAssignmentAnalysis(block, new JavaResolver());
            }

            for (final VariableDeclarationStatement declaration : variables) {
                final VariableInitializer initializer = declaration.getVariables().firstOrNullObject();
                final String variableName = initializer.getName();
                final Variable variable = initializer.getUserData(Keys.VARIABLE);

                declareVariableInBlock(analysis, block, declaration.getType(), variableName, variable, true);
            }
        }

        for (AstNode child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            run(child, daa);
        }
    }

    private void declareVariableInBlock(
        final DefiniteAssignmentAnalysis analysis,
        final BlockStatement block,
        final AstType type,
        final String variableName,
        final Variable variable,
        final boolean allowPassIntoLoops) {

        //
        // The point at which the variable would be declared if we decide to declare it in this block.
        //
        final StrongBox<Statement> declarationPoint = new StrongBox<>();

        final boolean canMoveVariableIntoSubBlocks = findDeclarationPoint(
            analysis,
            variableName,
            allowPassIntoLoops,
            block,
            declarationPoint
        );

        if (declarationPoint.get() == null) {
            //
            // The variable isn't used at all.
            //
            return;
        }

        if (canMoveVariableIntoSubBlocks) {
            for (final Statement statement : block.getStatements()) {
                if (statement instanceof ForStatement) {
                    final ForStatement forStatement = (ForStatement) statement;
                    final AstNodeCollection<Statement> initializers = forStatement.getInitializers();

                    if (initializers.size() == 1) {
                        if (tryConvertAssignmentExpressionIntoVariableDeclaration(initializers.firstOrNullObject(), type, variableName)) {
                            continue;
                        }
                    }
                }

                for (final AstNode child : statement.getChildren()) {
                    if (child instanceof BlockStatement) {
                        declareVariableInBlock(analysis, (BlockStatement) child, type, variableName, variable, allowPassIntoLoops);
                    }
                    else if (hasNestedBlocks(child)) {
                        for (final AstNode nestedChild : child.getChildren()) {
                            if (nestedChild instanceof BlockStatement) {
                                declareVariableInBlock(analysis, (BlockStatement) nestedChild, type, variableName, variable, allowPassIntoLoops);
                            }
                        }
                    }
                }
            }
        }
        else if (!tryConvertAssignmentExpressionIntoVariableDeclaration(declarationPoint.get(), type, variableName)) {
            variablesToDeclare.add(new VariableToDeclare(type, variableName, variable, declarationPoint.get()));
        }
    }

    public static boolean findDeclarationPoint(
        final DefiniteAssignmentAnalysis analysis,
        final VariableDeclarationStatement declaration,
        final BlockStatement block,
        final StrongBox<Statement> declarationPoint) {

        final String variableName = declaration.getVariables().firstOrNullObject().getName();

        return findDeclarationPoint(analysis, variableName, true, block, declarationPoint);
    }

    static boolean findDeclarationPoint(
        final DefiniteAssignmentAnalysis analysis,
        final String variableName,
        final boolean allowPassIntoLoops,
        final BlockStatement block,
        final StrongBox<Statement> declarationPoint) {

        declarationPoint.set(null);

        for (final Statement statement : block.getStatements()) {
            if (usesVariable(statement, variableName)) {
                if (declarationPoint.get() == null) {
                    declarationPoint.set(statement);
                }

                if (!canMoveVariableIntoSubBlock(statement, variableName, allowPassIntoLoops)) {
                    //
                    // If it's not possible to move the variable use into a nested block,
                    // we need to declare it in this block.
                    //
                    return false;
                }

                //
                // If we can move the variable into a sub-block, we need to ensure that the
                // remaining code does not use the variable that was assigned by the first
                // sub-block.
                //

                final Statement nextStatement = statement.getNextStatement();

                if (nextStatement != null) {
                    analysis.setAnalyzedRange(nextStatement, block);
                    analysis.analyze(variableName);

                    if (!analysis.getUnassignedVariableUses().isEmpty()) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    private static boolean canMoveVariableIntoSubBlock(
        final Statement statement,
        final String variableName,
        final boolean allowPassIntoLoops) {

        if (!allowPassIntoLoops &&
            (statement instanceof ForStatement ||
             statement instanceof ForEachStatement ||
             statement instanceof WhileStatement ||
             statement instanceof DoWhileStatement)) {

            return false;
        }

        if (statement instanceof ForStatement) {
            final ForStatement forStatement = (ForStatement) statement;

            //
            // ForStatement is a special ase: we can move the variable declarations into the initializer.
            //

            if (forStatement.getInitializers().size() == 1) {
                final Statement initializer = forStatement.getInitializers().firstOrNullObject();

                if (initializer instanceof ExpressionStatement &&
                    ((ExpressionStatement) initializer).getExpression() instanceof AssignmentExpression) {

                    final Expression e = ((ExpressionStatement) initializer).getExpression();

                    if (e instanceof AssignmentExpression &&
                        ((AssignmentExpression) e).getOperator() == AssignmentOperatorType.ASSIGN &&
                        ((AssignmentExpression) e).getLeft() instanceof IdentifierExpression) {

                        final IdentifierExpression identifier = (IdentifierExpression) ((AssignmentExpression) e).getLeft();

                        if (StringUtilities.equals(identifier.getIdentifier(), variableName)) {
                            return !usesVariable(((AssignmentExpression) e).getRight(), variableName);
                        }
                    }
                }
            }
        }

        //
        // We can move the variable into a sub-block only if the variable is used only in that
        // sub-block (and not in expressions such as the loop condition).
        //

        for (AstNode child = statement.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (!(child instanceof BlockStatement) && usesVariable(child, variableName)) {
                if (hasNestedBlocks(child)) {
                    //
                    // Catch clauses and switch sections can contain nested blocks.
                    //
                    for (AstNode grandChild = child.getFirstChild(); grandChild != null; grandChild = grandChild.getNextSibling()) {
                        if (!(grandChild instanceof BlockStatement) && usesVariable(grandChild, variableName)) {
                            return false;
                        }
                    }
                }
                else {
                    return false;
                }
            }
        }

        return true;
    }

    private static boolean usesVariable(final AstNode node, final String variableName) {
        if (node instanceof IdentifierExpression) {
            if (StringUtilities.equals(((IdentifierExpression) node).getIdentifier(), variableName)) {
                return true;
            }
        }

        if (node instanceof ForEachStatement) {
            if (StringUtilities.equals(((ForEachStatement) node).getVariableName(), variableName)) {
                //
                // No need to introduce the variable here.
                //
                return false;
            }
        }

        if (node instanceof CatchClause) {
            if (StringUtilities.equals(((CatchClause) node).getVariableName(), variableName)) {
                //
                // No need to introduce the variable here.
                //
                return false;
            }
        }

        for (AstNode child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (usesVariable(child, variableName)) {
                return true;
            }
        }

        return false;
    }

    private static int getAssignmentCount(final AstNode node, final String variableName) {
        int count = 0;

        for (final AstNode d : node.getDescendantsAndSelf()) {
            if (d instanceof AssignmentExpression) {
                final AssignmentExpression assignment = (AssignmentExpression) d;
                final Expression left = assignment.getLeft();

                if (left instanceof IdentifierExpression &&
                    StringUtilities.equals(((IdentifierExpression) left).getIdentifier(), variableName)) {

                    ++count;
                }
            }
        }

        return count;
    }

    private static boolean hasNestedBlocks(final AstNode node) {
        return node instanceof CatchClause || node instanceof SwitchSection;
    }

    private boolean tryConvertAssignmentExpressionIntoVariableDeclaration(
        final Statement declarationPoint,
        final AstType type,
        final String variableName) {

        return declarationPoint instanceof ExpressionStatement &&
               tryConvertAssignmentExpressionIntoVariableDeclaration(
                   ((ExpressionStatement) declarationPoint).getExpression(),
                   type,
                   variableName
               );
    }

    private boolean tryConvertAssignmentExpressionIntoVariableDeclaration(
        final Expression expression,
        final AstType type,
        final String variableName) {

        if (expression instanceof AssignmentExpression) {
            final AssignmentExpression assignment = (AssignmentExpression) expression;

            if (assignment.getOperator() == AssignmentOperatorType.ASSIGN) {
                if (assignment.getLeft() instanceof IdentifierExpression) {
                    final IdentifierExpression identifier = (IdentifierExpression) assignment.getLeft();

                    if (StringUtilities.equals(identifier.getIdentifier(), variableName)) {
                        variablesToDeclare.add(
                            new VariableToDeclare(
                                type,
                                variableName,
                                identifier.getUserData(Keys.VARIABLE),
                                assignment
                            )
                        );

                        return true;
                    }
                }
            }
        }

        return false;
    }

    // <editor-fold defaultstate="collapsed" desc="VariableToDeclare Class">

    protected final static class VariableToDeclare {
        private final AstType _type;
        private final String _name;
        private final Variable _variable;
        private final Statement _insertionPoint;
        private final AssignmentExpression _replacedAssignment;

        private boolean _effectivelyFinal;

        public VariableToDeclare(
            final AstType type,
            final String name,
            final Variable variable,
            final Statement insertionPoint) {

            _type = type;
            _name = name;
            _variable = variable;
            _insertionPoint = insertionPoint;
            _replacedAssignment = null;
        }

        public VariableToDeclare(
            final AstType type,
            final String name,
            final Variable variable,
            final AssignmentExpression replacedAssignment) {

            _type = type;
            _name = name;
            _variable = variable;
            _insertionPoint = null;
            _replacedAssignment = replacedAssignment;
        }

        public AstType getType() {
            return _type;
        }

        public String getName() {
            return _name;
        }

        public Variable getVariable() {
            return _variable;
        }

        public AssignmentExpression getReplacedAssignment() {
            return _replacedAssignment;
        }

        public Statement getInsertionPoint() {
            return _insertionPoint;
        }

        public boolean isEffectivelyFinal() {
            return _effectivelyFinal;
        }

        public void setEffectivelyFinal(final boolean effectivelyFinal) {
            _effectivelyFinal = effectivelyFinal;
        }

        @Override
        public String toString() {
            return "VariableToDeclare{" +
                   "Type=" + _type +
                   ", Name='" + _name + '\'' +
                   ", Variable=" + _variable +
                   ", InsertionPoint=" + _insertionPoint +
                   ", ReplacedAssignment=" + _replacedAssignment +
                   ", EffectivelyFinal=" + _effectivelyFinal +
                   '}';
        }
    }

    // </editor-fold>
}