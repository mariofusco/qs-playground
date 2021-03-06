package org.drools.modelcompiler.builder.generator.operatorspec;

import static org.drools.modelcompiler.builder.generator.DslMethodNames.EVAL_CALL;

import java.util.Optional;

import org.drools.javaparser.ast.drlx.expr.PointFreeExpr;
import org.drools.javaparser.ast.expr.MethodCallExpr;
import org.drools.javaparser.ast.expr.StringLiteralExpr;
import org.drools.javaparser.ast.expr.UnaryExpr;
import org.drools.model.functions.Operator;
import org.drools.modelcompiler.builder.generator.RuleContext;
import org.drools.modelcompiler.builder.generator.TypedExpression;
import org.drools.modelcompiler.builder.generator.drlxparse.CoercedExpression;
import org.drools.modelcompiler.builder.generator.expressiontyper.ExpressionTyper;

public class NativeOperatorSpec implements OperatorSpec {
    public static final NativeOperatorSpec INSTANCE = new NativeOperatorSpec();

    public Expression getExpression(RuleContext context, PointFreeExpr pointFreeExpr, TypedExpression left, ExpressionTyper expressionTyper) {
        MethodCallExpr methodCallExpr = new MethodCallExpr( null, EVAL_CALL );

        String opName = pointFreeExpr.getOperator().asString();
        Operator operator = addOperatorArgument( context, methodCallExpr, opName );

        methodCallExpr.addArgument( left.getExpression() );
        for (Expression rightExpr : pointFreeExpr.getRight()) {
            final Optional<TypedExpression> optionalRight = expressionTyper.toTypedExpression(rightExpr).getTypedExpression();
            optionalRight.ifPresent( right -> {
                final TypedExpression coercedRight;
                if (operator != null && operator.requiresCoercion()) {
                    final CoercedExpression.CoercedExpressionResult coerce = new CoercedExpression(left, right).coerce();
                    coercedRight = coerce.getCoercedRight();
                } else {
                    coercedRight = right;
                }
                methodCallExpr.addArgument(coercedRight.getExpression() );
            });
        }

        return pointFreeExpr.isNegated() ?
                new UnaryExpr( methodCallExpr, UnaryExpr.Operator.LOGICAL_COMPLEMENT ) :
                methodCallExpr;
    }

    protected Operator addOperatorArgument( RuleContext context, MethodCallExpr methodCallExpr, String opName ) {
        Operator operator = Operator.Register.getOperator( opName );
        try {
            // if the operator has an INSTANCE field avoid the operator lookup at runtime
            operator.getClass().getField( "INSTANCE" );
            methodCallExpr.addArgument( operator.getClass().getCanonicalName() + ".INSTANCE" );
        } catch (NoSuchFieldException e) {
            methodCallExpr.addArgument( new StringLiteralExpr( opName ) );
        }
        return operator;
    }

    @Override
    public boolean isStatic() {
        return false;
    }
}
