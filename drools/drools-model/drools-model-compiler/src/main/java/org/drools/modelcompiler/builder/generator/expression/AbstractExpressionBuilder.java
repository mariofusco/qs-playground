/*
 * Copyright 2005 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.drools.modelcompiler.builder.generator.expression;

import static org.drools.modelcompiler.builder.generator.DrlxParseUtil.generateLambdaWithoutParameters;
import static org.drools.modelcompiler.builder.generator.DrlxParseUtil.toClassOrInterfaceType;
import static org.drools.modelcompiler.util.ClassUtil.toRawClass;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.Objects;
import java.util.stream.Stream;

import javax.lang.model.type.PrimitiveType;

import org.drools.javaparser.ast.expr.BigDecimalLiteralExpr;
import org.drools.javaparser.ast.expr.BigIntegerLiteralExpr;
import org.drools.javaparser.ast.expr.CastExpr;
import org.drools.javaparser.ast.expr.EnclosedExpr;
import org.drools.javaparser.ast.expr.FieldAccessExpr;
import org.drools.javaparser.ast.expr.LambdaExpr;
import org.drools.javaparser.ast.expr.LiteralExpr;
import org.drools.javaparser.ast.expr.MethodCallExpr;
import org.drools.javaparser.ast.expr.NameExpr;
import org.drools.javaparser.ast.expr.NullLiteralExpr;
import org.drools.javaparser.ast.expr.ObjectCreationExpr;
import org.drools.javaparser.ast.expr.StringLiteralExpr;
import org.drools.javaparser.ast.stmt.ExpressionStmt;
import org.drools.javaparser.ast.type.UnknownType;
import org.drools.modelcompiler.builder.generator.DrlxParseUtil;
import org.drools.modelcompiler.builder.generator.IndexIdGenerator;
import org.drools.modelcompiler.builder.generator.RuleContext;
import org.drools.modelcompiler.builder.generator.TypedExpression;
import org.drools.modelcompiler.builder.generator.drlxparse.DrlxParseSuccess;
import org.drools.modelcompiler.builder.generator.drlxparse.MultipleDrlxParseSuccess;
import org.drools.modelcompiler.builder.generator.drlxparse.SingleDrlxParseSuccess;
import org.drools.modelcompiler.util.ClassUtil;
import org.w3c.dom.NodeList;

public abstract class AbstractExpressionBuilder {
    protected static final IndexIdGenerator indexIdGenerator = new IndexIdGenerator();

    protected RuleContext context;

    protected AbstractExpressionBuilder( RuleContext context ) {
        this.context = context;
    }

    public void processExpression(DrlxParseSuccess drlxParseResult) {
        if (drlxParseResult instanceof SingleDrlxParseSuccess) {
            processExpression( (SingleDrlxParseSuccess) drlxParseResult );
        } else if (drlxParseResult instanceof MultipleDrlxParseSuccess) {
            processExpression( (MultipleDrlxParseSuccess) drlxParseResult );
        } else {
            throw new UnsupportedOperationException( "Unknown expression type: " + drlxParseResult.getClass().getName() );
        }
    }

    public void processExpression(SingleDrlxParseSuccess drlxParseResult) {
        if (drlxParseResult.hasUnificationVariable()) {
            Expression dslExpr = buildUnificationExpression(drlxParseResult);
            context.addExpression(dslExpr);
        } else if ( drlxParseResult.isValidExpression() ) {
            Expression dslExpr = buildExpressionWithIndexing(drlxParseResult);
            context.addExpression(dslExpr);
        }
        if (drlxParseResult.getExprBinding() != null) {
            Expression dslExpr = buildBinding(drlxParseResult);
            context.addExpression(dslExpr);
        }
    }

    public void processExpression(MultipleDrlxParseSuccess drlxParseResult) {
        if ( drlxParseResult.isValidExpression() ) {
            Expression dslExpr = buildExpressionWithIndexing(drlxParseResult);
            context.addExpression(dslExpr);
        }
    }

    private Expression buildUnificationExpression(SingleDrlxParseSuccess drlxParseResult) {
        MethodCallExpr exprDSL = buildBinding(drlxParseResult);
        context.addDeclaration(drlxParseResult.getUnificationVariable(), drlxParseResult.getUnificationVariableType(), drlxParseResult.getUnificationName());
        return exprDSL;
    }

    public abstract MethodCallExpr buildExpressionWithIndexing(DrlxParseSuccess drlxParseResult);

    public abstract MethodCallExpr buildBinding(SingleDrlxParseSuccess drlxParseResult );

    protected Expression getConstraintExpression(SingleDrlxParseSuccess drlxParseResult) {
        if (drlxParseResult.getExpr() instanceof EnclosedExpr) {
            return buildConstraintExpression(drlxParseResult, ((EnclosedExpr) drlxParseResult.getExpr()).getInner());
        } else {
            final TypedExpression left = drlxParseResult.getLeft();
            // Can we unify it? Sometimes expression is in the left sometimes in expression
            final Expression e;
            if(left != null) {
                e = DrlxParseUtil.findLeftLeafOfMethodCall(left.getExpression());
            } else {
                e = drlxParseResult.getExpr();
            }
            return buildConstraintExpression(drlxParseResult, drlxParseResult.getUsedDeclarationsOnLeft(), e);
        }
    }

    protected Expression buildConstraintExpression(SingleDrlxParseSuccess drlxParseResult, Expression expr ) {
        return buildConstraintExpression(drlxParseResult, drlxParseResult.getUsedDeclarations(), expr );
    }

    protected Expression buildConstraintExpression(SingleDrlxParseSuccess drlxParseResult, Collection<String> usedDeclarations, Expression expr ) {
        return drlxParseResult.isStatic() ? expr : generateLambdaWithoutParameters(usedDeclarations, expr, drlxParseResult.isSkipThisAsParam());
    }

    boolean hasIndex( SingleDrlxParseSuccess drlxParseResult ) {
        TypedExpression left = drlxParseResult.getLeft();
        Collection<String> usedDeclarations = drlxParseResult.getUsedDeclarations();

        return drlxParseResult.getDecodeConstraintType() != null && left.getFieldName() != null && !isThisExpression( left.getExpression() ) &&
                ( isAlphaIndex( usedDeclarations ) || isBetaIndex( usedDeclarations, drlxParseResult.getRight() ) );
    }

    boolean isAlphaIndex( Collection<String> usedDeclarations ) {
        return usedDeclarations.isEmpty();
    }

    private boolean isBetaIndex( Collection<String> usedDeclarations, TypedExpression right ) {
        // a Beta node should NOT create the index when the "right" is not just-a-symbol, the "right" is not a declaration referenced by name
        return usedDeclarations.size() == 1 && context.getDeclarationById( getExpressionSymbolForBetaIndex( right.getExpression() ) ).isPresent();
    }

    private static String getExpressionSymbolForBetaIndex(Expression expr) {
        Expression scope;
        if (expr instanceof MethodCallExpr && (( MethodCallExpr ) expr).getScope().isPresent()) {
            scope = (( MethodCallExpr ) expr).getScope().get();
        } else if (expr instanceof FieldAccessExpr ) {
            scope = (( FieldAccessExpr ) expr).getScope();
        } else {
            scope = expr;
        }
        return scope instanceof NameExpr ? (( NameExpr ) scope).getNameAsString() : null;
    }

    private boolean isThisExpression( Expression leftExpr ) {
        return leftExpr instanceof NameExpr && ((NameExpr)leftExpr).getName().getIdentifier().equals("_this");
    }

    public static AbstractExpressionBuilder getExpressionBuilder(RuleContext context) {
        return context.isPatternDSL() ? new PatternExpressionBuilder( context ) : new FlowExpressionBuilder( context );
    }

    protected Expression narrowExpressionToType( TypedExpression right, java.lang.reflect.Type leftType ) {
        Expression expression = right.getExpression();

        if (expression instanceof NullLiteralExpr) {
            return expression;
        }

        if (leftType.equals(Double.class)) {
            expression = new CastExpr( PrimitiveType.doubleType(), expression );
        } else if (leftType.equals(Long.class)) {
            if (right.getType().equals( Double.class ) || right.getType().equals( double.class )) {
                expression = new MethodCallExpr( expression, "longValue" );
            } else {
                expression = new CastExpr( PrimitiveType.longType(), expression );
            }

        } else if (expression instanceof LiteralExpr) {
            if(expression instanceof BigDecimalLiteralExpr) {
                expression = toNewExpr(BigDecimal.class, new StringLiteralExpr(((BigDecimalLiteralExpr) expression).asBigDecimal().toString()));
            } else if (expression instanceof BigIntegerLiteralExpr) {
                expression = toNewExpr(toRawClass(leftType), new StringLiteralExpr(((BigIntegerLiteralExpr) expression).asBigInteger().toString()));
            } else if (leftType.equals(BigDecimal.class)) {
                final BigDecimal bigDecimal = new BigDecimal( expression.toString() );
                expression = toNewExpr(BigDecimal.class, new StringLiteralExpr( bigDecimal.toString() ) );
            } else if (leftType.equals(BigInteger.class)) {
                final BigInteger bigInteger = new BigDecimal(expression.toString()).toBigInteger();
                expression = toNewExpr(BigInteger.class, new StringLiteralExpr(bigInteger.toString()));
            }

        } else if (expression instanceof NameExpr) {
            if (leftType.equals(BigDecimal.class)) {
                expression = toNewExpr(BigDecimal.class, expression);
            } else if (leftType.equals(BigInteger.class)) {
                expression = toNewExpr(BigInteger.class, expression);
            }
        }

        return expression;
    }

    private static Expression toNewExpr(Class<?> clazz, Expression initExpression) {
        return new ObjectCreationExpr(null, toClassOrInterfaceType(clazz), NodeList.nodeList(initExpression));
    }

    protected void addIndexedByDeclaration(TypedExpression left, TypedExpression right, boolean leftContainsThis, MethodCallExpr indexedByDSL, Collection<String> usedDeclarations, java.lang.reflect.Type leftType) {
        LambdaExpr indexedBy_rightOperandExtractor = new LambdaExpr();
        indexedBy_rightOperandExtractor.addParameter(new Parameter(new UnknownType(), usedDeclarations.iterator().next()));
        final TypedExpression expression;
        if (!leftContainsThis) {
            expression = left;
        } else {
            expression = right;
        }
        final Expression narrowed = narrowExpressionToType(expression, leftType);
        indexedBy_rightOperandExtractor.setBody(new ExpressionStmt(narrowed));
        indexedByDSL.addArgument(indexedBy_rightOperandExtractor);
    }

    protected Class<?> getIndexType( TypedExpression left, TypedExpression right ) {
        return Stream.of(left, right).map(TypedExpression::getType)
                .filter(Objects::nonNull)
                .map(ClassUtil::toRawClass)
                .findFirst().get();
    }
}
