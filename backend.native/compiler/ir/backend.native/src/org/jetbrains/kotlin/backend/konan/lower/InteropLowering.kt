/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.backend.konan.lower

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.lower.IrBuildingTransformer
import org.jetbrains.kotlin.backend.common.lower.at
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.ValueType
import org.jetbrains.kotlin.backend.konan.isRepresentedAs
import org.jetbrains.kotlin.backend.konan.reportCompilationError
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.ir.builders.IrBuilder
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrCallableReferenceImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetObjectValueImpl
import org.jetbrains.kotlin.ir.util.getArguments
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.OverridingUtil
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.scopes.MemberScope
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.TypeUtils
import org.jetbrains.kotlin.types.typeUtil.isSubtypeOf

/**
 * Lowers some interop intrinsic calls.
 */
internal class InteropLowering(val context: Context) : FileLoweringPass {
    override fun lower(irFile: IrFile) {
        val transformer = InteropTransformer(context, irFile)
        irFile.transformChildrenVoid(transformer)
    }
}

private class InteropTransformer(val context: Context, val irFile: IrFile) : IrBuildingTransformer(context) {

    val interop = context.interopBuiltIns

    private fun MemberScope.getSingleContributedFunction(name: String,
                                                         predicate: (SimpleFunctionDescriptor) -> Boolean) =
            this.getContributedFunctions(Name.identifier(name), NoLookupLocation.FROM_BACKEND).single(predicate)

    private fun IrBuilder.irGetObject(descriptor: ClassDescriptor): IrExpression {
        return IrGetObjectValueImpl(startOffset, endOffset, descriptor.defaultType, descriptor)
    }

    private fun IrBuilder.typeOf(descriptor: ClassDescriptor): IrExpression {
        val companionObject = descriptor.companionObjectDescriptor ?:
                error("native variable class $descriptor must have the companion object")
        // TODO: add more checks and produce the compile error instead of exception.

        return irGetObject(companionObject)
    }

    private fun IrBuilder.typeOf(type: KotlinType): IrExpression? {
        val descriptor = TypeUtils.getClassDescriptor(type) ?: return null
        return typeOf(descriptor)
    }

    private fun KotlinType.findOverride(property: PropertyDescriptor): PropertyDescriptor {
        val result = this.memberScope.getContributedVariables(property.name, NoLookupLocation.FROM_BACKEND).single()
        assert (OverridingUtil.overrides(result, property))
        return result
    }

    private fun IrBuilderWithScope.sizeOf(typeObject: IrExpression): IrExpression {
        val sizeProperty = typeObject.type.findOverride(interop.variableTypeSize)
        return irGet(typeObject, sizeProperty)
    }

    private fun IrBuilderWithScope.sizeOf(type: KotlinType): IrExpression? {
        val typeObject = typeOf(type) ?: return null
        return sizeOf(typeObject)
    }

    private fun IrBuilderWithScope.alignOf(typeObject: IrExpression): IrExpression {
        val alignProperty = typeObject.type.findOverride(interop.variableTypeAlign)
        return irGet(typeObject, alignProperty)
    }

    private fun IrBuilderWithScope.alignOf(type: KotlinType): IrExpression? {
        val typeObject = typeOf(type) ?: return null
        return alignOf(typeObject)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun IrBuilderWithScope.interpretPointed(expression: IrCall, type: KotlinType): IrExpression =
            irCall(interop.interpretNullablePointed).apply {
                putValueArgument(0, expression)
            }
    // TODO: make the result type more correct.

    private fun IrBuilderWithScope.arrayGet(array: IrExpression, index: IrExpression): IrExpression? {
        val elementType = array.type.arguments.single().type
        val elementSize = sizeOf(elementType) ?: return null

        val resultRawPtr = irCall(interop.nativePtrPlusLong).apply {
            dispatchReceiver = irCall(interop.cPointerGetRawValue).apply {
                extensionReceiver = array
            }
            putValueArgument(0, times(elementSize, index))
        }
        return interpretPointed(resultRawPtr, elementType)
    }

    private fun IrBuilderWithScope.times(left: IrExpression, right: IrExpression): IrCall {
        val times = left.type.memberScope.getSingleContributedFunction("times") {
            right.type.isSubtypeOf(it.valueParameters.single().type)
        }

        return irCall(times).apply {
            dispatchReceiver = left
            putValueArgument(0, right)
        }
    }

    private fun IrBuilderWithScope.alloc(placement: IrExpression, size: IrExpression, align: IrExpression): IrExpression {
        val alloc = placement.type.memberScope.getSingleContributedFunction("alloc") {
            size.type.isSubtypeOf(it.valueParameters[0]!!.type) &&
                    align.type.isSubtypeOf(it.valueParameters[1]!!.type)
        }

        return irCall(alloc).apply {
            dispatchReceiver = placement
            putValueArgument(0, size)
            putValueArgument(1, align)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun IrBuilderWithScope.nativePointedToCPointer(
            expression: IrExpression, pointeeType: KotlinType
    ): IrExpression = irCall(interop.interpretCPointer).apply {
        putValueArgument(0, irCall(interop.nativePointedGetRawPointer).apply {
            extensionReceiver = expression
        })
    }
    // TODO: make the result type more correct.

    private fun IrBuilderWithScope.allocArray(placement: IrExpression,
                                              elementType: KotlinType,
                                              length: IrExpression
    ): IrExpression? {

        val elementSize = sizeOf(elementType) ?: return null
        val size = times(elementSize, length)
        val align = alignOf(elementType) ?: return null

        return nativePointedToCPointer(alloc(placement, size, align), elementType)
    }

    private fun IrBuilderWithScope.alloc(placement: IrExpression, type: KotlinType): IrExpression? {
        val size = sizeOf(type) ?: return null
        val align = alignOf(type) ?: return null

        return alloc(placement, size, align)
    }

    override fun visitCall(expression: IrCall): IrExpression {

        expression.transformChildrenVoid(this)
        builder.at(expression)
        val descriptor = expression.descriptor.original

        if (descriptor is ClassConstructorDescriptor) {
            val type = descriptor.constructedClass.defaultType
            if (type.isRepresentedAs(ValueType.C_POINTER) || type.isRepresentedAs(ValueType.NATIVE_POINTED)) {
                throw Error("Native interop types constructors must not be called directly")
            }
        }

        if (descriptor == interop.nativePointedRawPtrGetter ||
                OverridingUtil.overrides(descriptor, interop.nativePointedRawPtrGetter)) {

            // Replace by the intrinsic call to be handled by code generator:
            return builder.irCall(interop.nativePointedGetRawPointer).apply {
                extensionReceiver = expression.dispatchReceiver
            }
        }

        return when (descriptor) {
            interop.cPointerRawValue.getter ->
                // Replace by the intrinsic call to be handled by code generator:
                builder.irCall(interop.cPointerGetRawValue).apply {
                    extensionReceiver = expression.dispatchReceiver
                }

            interop.arrayGetByIntIndex, interop.arrayGetByLongIndex -> {
                val array = expression.extensionReceiver!!
                val index = expression.getValueArgument(0)!!
                builder.arrayGet(array, index) ?: expression
            }

            interop.allocUninitializedArrayWithIntLength, interop.allocUninitializedArrayWithLongLength -> {
                val placement = expression.extensionReceiver!!
                val elementType = expression.type.arguments.single().type
                val length = expression.getValueArgument(0)!!
                builder.allocArray(placement, elementType, length) ?: expression
            }

            interop.allocVariable -> {
                val placement = expression.extensionReceiver!!
                val type = expression.getSingleTypeArgument()
                builder.alloc(placement, type) ?: expression
            }

            interop.typeOf -> {
                val type = expression.getSingleTypeArgument()
                builder.typeOf(type) ?: expression
            }

            interop.bitsToFloat -> {
                val argument = expression.getValueArgument(0)
                if (argument is IrConst<*> && argument.kind == IrConstKind.Int) {
                    val floatValue = kotlinx.cinterop.bitsToFloat(argument.value as Int)
                    builder.irFloat(floatValue)
                } else {
                    expression
                }
            }

            interop.bitsToDouble -> {
                val argument = expression.getValueArgument(0)
                if (argument is IrConst<*> && argument.kind == IrConstKind.Long) {
                    val doubleValue = kotlinx.cinterop.bitsToDouble(argument.value as Long)
                    builder.irDouble(doubleValue)
                } else {
                    expression
                }
            }

            interop.staticCFunction -> {
                val argument = expression.getValueArgument(0)!!
                if (argument !is IrCallableReference || argument.getArguments().isNotEmpty()) {
                    context.reportCompilationError(
                            "${interop.staticCFunction.fqNameSafe} must take an unbound, non-capturing function",
                            irFile, expression
                    )
                    // TODO: should probably be reported during analysis.
                }

                val cFunctionType = expression.getTypeArgument(descriptor.typeParameters[1])!!

                if (interop.isTriviallyAdaptedFunctionType(cFunctionType)) {
                    IrCallableReferenceImpl(
                            builder.startOffset, builder.endOffset,
                            expression.type,
                            argument.descriptor,
                            typeArguments = null)
                } else {
                    TODO("$cFunctionType requiring non-trivial C adapter")
                }
            }

            interop.signExtend, interop.narrow -> {

                val integerTypePredicates = arrayOf(
                        KotlinBuiltIns::isByte, KotlinBuiltIns::isShort, KotlinBuiltIns::isInt, KotlinBuiltIns::isLong
                )

                val receiver = expression.extensionReceiver!!
                val typeOperand = expression.getSingleTypeArgument()

                val receiverTypeIndex = integerTypePredicates.indexOfFirst { it(receiver.type) }
                val typeOperandIndex = integerTypePredicates.indexOfFirst { it(typeOperand) }

                if (receiverTypeIndex == -1) {
                    context.reportCompilationError("Receiver's type ${receiver.type} is not an integer type",
                            irFile, receiver)
                }

                if (typeOperandIndex == -1) {
                    context.reportCompilationError("Type argument $typeOperand is not an integer type",
                            irFile, expression)
                }

                when (descriptor) {
                    interop.signExtend -> if (receiverTypeIndex > typeOperandIndex) {
                        context.reportCompilationError("unable to sign extend ${receiver.type} to $typeOperand",
                                irFile, expression)
                    }

                    interop.narrow -> if (receiverTypeIndex < typeOperandIndex) {
                        context.reportCompilationError("unable to narrow ${receiver.type} to $typeOperand",
                                irFile, expression)
                    }

                    else -> throw Error()
                }

                val conversionDescriptor = receiver.type.memberScope.getContributedFunctions(
                        Name.identifier("to$typeOperand"), NoLookupLocation.FROM_BACKEND).single()

                builder.irCall(conversionDescriptor).apply {
                    dispatchReceiver = receiver
                }
            }

            else -> expression
        }
    }

    private fun IrCall.getSingleTypeArgument(): KotlinType {
        val typeParameter = descriptor.original.typeParameters.single()
        return getTypeArgument(typeParameter)!!
    }
}

private fun IrBuilder.irFloat(value: Float) =
        IrConstImpl.float(startOffset, endOffset, context.builtIns.floatType, value)

private fun IrBuilder.irDouble(value: Double) =
        IrConstImpl.double(startOffset, endOffset, context.builtIns.doubleType, value)