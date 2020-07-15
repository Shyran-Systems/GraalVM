/*
 * Copyright (c) 2020, 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.espresso.substitutions;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.espresso.impl.Field;
import com.oracle.truffle.espresso.impl.Klass;
import com.oracle.truffle.espresso.meta.EspressoError;
import com.oracle.truffle.espresso.meta.Meta;
import com.oracle.truffle.espresso.runtime.StaticObject;
import com.oracle.truffle.espresso.vm.InterpreterToVM;

import java.util.NoSuchElementException;

@EspressoSubstitutions
public class Target_com_oracle_truffle_espresso_polyglot_Polyglot {
    @Substitution
    public static boolean isInteropObject(@Host(Object.class) StaticObject object) {
        return object.isInteropObject();
    }

    @Substitution
    public static @Host(Object.class) StaticObject cast(@Host(Class.class) StaticObject targetClass, @Host(Object.class) StaticObject value, @InjectMeta Meta meta) {
        if (StaticObject.isNull(value)) {
            return value;
        }
        Klass targetKlass = targetClass.getMirrorKlass();
        if (value.isInteropObject()) {
            if (targetKlass.isPrimitive()) {
                try {
                    return castToBoxed(targetKlass, value.rawInteropObject(), meta);
                } catch (UnsupportedMessageException e) {
                    throw Meta.throwExceptionWithMessage(meta.java_lang_ClassCastException,
                                    String.format("Couldn't read %s value from interop object", targetKlass.getTypeAsString()));
                }
            }

            try {
                checkHasAllFieldsOrThrow(value.rawInteropObject(), targetKlass, InteropLibrary.getUncached());
            } catch (NoSuchElementException e) {
                throw Meta.throwExceptionWithMessage(meta.java_lang_ClassCastException,
                                String.format("Field %s not found", e.getMessage()));
            }

            return StaticObject.createInterop(targetKlass, value.rawInteropObject());
        } else {
            return InterpreterToVM.checkCast(value, targetKlass);
        }
    }

    private static void checkHasAllFieldsOrThrow(Object interopObject, Klass klass, InteropLibrary interopLibrary) {
        for (Field f : klass.getDeclaredFields()) {
            if (!f.isStatic() && interopLibrary.isMemberExisting(interopObject, f.getNameAsString())) {
                throw new NoSuchElementException(f.getNameAsString());
            }
        }
        if (klass.getSuperClass() != null) {
            checkHasAllFieldsOrThrow(interopObject, klass.getSuperKlass(), interopLibrary);
        }
    }

    private static StaticObject castToBoxed(Klass targetKlass, Object interopValue, Meta meta) throws UnsupportedMessageException {
        InteropLibrary interopLibrary = InteropLibrary.getUncached();
        if (targetKlass == meta._boolean) {
            boolean boolValue = interopLibrary.asBoolean(interopValue);
            return meta.boxBoolean(boolValue);
        }
        if (targetKlass == meta._char) {
            String value = interopLibrary.asString(interopValue);
            if (value.length() != 1) {
                throw Meta.throwExceptionWithMessage(meta.java_lang_ClassCastException,
                                String.format("Cannot cast string %s to char", value));
            }
            return meta.boxCharacter(value.charAt(0));
        }
        if (targetKlass == meta._byte) {
            byte byteValue = interopLibrary.asByte(interopValue);
            return meta.boxByte(byteValue);
        }
        if (targetKlass == meta._short) {
            short shortValue = interopLibrary.asShort(interopValue);
            return meta.boxShort(shortValue);
        }
        if (targetKlass == meta._int) {
            int intValue = interopLibrary.asInt(interopValue);
            return meta.boxInteger(intValue);
        }
        if (targetKlass == meta._long) {
            long longValue = interopLibrary.asLong(interopValue);
            return meta.boxLong(longValue);
        }
        if (targetKlass == meta._float) {
            float floatValue = interopLibrary.asFloat(interopValue);
            return meta.boxFloat(floatValue);
        }
        if (targetKlass == meta._double) {
            double doubleValue = interopLibrary.asDouble(interopValue);
            return meta.boxDouble(doubleValue);
        }
        if (targetKlass == meta._void) {
            throw Meta.throwExceptionWithMessage(meta.java_lang_ClassCastException, "Cannot cast to void");
        }
        throw EspressoError.shouldNotReachHere("Unexpected primitive klass: ", targetKlass);
    }

    @Substitution
    public static @Host(Object.class) StaticObject eval(@Host(String.class) StaticObject language, @Host(String.class) StaticObject code, @InjectMeta Meta meta) {
        Source source = Source.newBuilder(language.toString(), code.toString(), "(eval)").build();
        Object evalResult = meta.getContext().getEnv().parsePublic(source).call();
        if (evalResult instanceof StaticObject) {
            return (StaticObject) evalResult;
        }
        return createInteropObject(evalResult, meta);
    }

    @Substitution
    public static @Host(Object.class) StaticObject importObject(@Host(String.class) StaticObject name, @InjectMeta Meta meta) {
        if (!meta.getContext().getEnv().isPolyglotBindingsAccessAllowed()) {
            Meta.throwExceptionWithMessage(meta.java_lang_SecurityException,
                            "Polyglot bindings are not accessible for this language. Use --polyglot or allowPolyglotAccess when building the context.");
        }
        Object binding = meta.getContext().getEnv().importSymbol(name.toString());
        if (binding == null) {
            return StaticObject.NULL;
        }
        if (binding instanceof StaticObject) {
            return (StaticObject) binding;
        }
        return createInteropObject(binding, meta);
    }

    @Substitution
    public static void exportObject(@Host(String.class) StaticObject name, @Host(Object.class) StaticObject value, @InjectMeta Meta meta) {
        if (!meta.getContext().getEnv().isPolyglotBindingsAccessAllowed()) {
            Meta.throwExceptionWithMessage(meta.java_lang_SecurityException,
                            "Polyglot bindings are not accessible for this language. Use --polyglot or allowPolyglotAccess when building the context.");
        }
        if (value.isInteropObject()) {
            meta.getContext().getEnv().exportSymbol(name.toString(), value.rawInteropObject());
        } else {
            meta.getContext().getEnv().exportSymbol(name.toString(), value);
        }
    }

    protected static StaticObject createInteropObject(Object object, Meta meta) {
        return StaticObject.createInterop(meta.java_lang_Object, object);
    }
}
