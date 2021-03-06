/*
 * Copyright 2020 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kie.dmn.typesafe;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Period;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.drools.core.util.StringUtils;
import org.drools.modelcompiler.builder.generator.declaredtype.api.AnnotationDefinition;
import org.drools.modelcompiler.builder.generator.declaredtype.api.FieldDefinition;
import org.drools.modelcompiler.builder.generator.declaredtype.api.SimpleAnnotationDefinition;
import org.kie.dmn.api.core.DMNType;
import org.kie.dmn.feel.codegen.feel11.CodegenStringUtil;

import static com.github.javaparser.StaticJavaParser.parseClassOrInterfaceType;

public class DMNDeclaredField implements FieldDefinition {

    private static final String OBJECT_TYPE = "Object";

    private DMNAllTypesIndex index;
    private String fieldName;
    private String originalMapKey;
    private DMNType fieldDMNType;

    DMNDeclaredField(DMNAllTypesIndex index, Map.Entry<String, DMNType> dmnField) {
        this.index = index;
        this.fieldName = CodegenStringUtil.escapeIdentifier(StringUtils.lcFirst(dmnField.getKey()));
        this.originalMapKey = dmnField.getKey();
        this.fieldDMNType = dmnField.getValue();
    }

    @Override
    public String getFieldName() {
        return fieldName;
    }

    public String getOriginalMapKey() {
        return originalMapKey;
    }

    @Override
    public String getObjectType() {
        if (fieldDMNType.isCollection()) {
            String typeName = getBaseType(fieldDMNType);
            String typeNameWithPackage = withPackage(typeName);
            String convertedType = convertType(typeNameWithPackage);
            return String.format("java.util.Collection<%s>", convertedType);
        } else {
            return fieldTypeWithPackage();
        }
    }

    private String fieldTypeWithPackage() {
        String fieldNameWithAnyCheck = getRecursiveBaseType(fieldDMNType)
                .map(this::getFieldNameWithAnyCheck)
                .orElse(Object.class.getCanonicalName());
        String withPackage = withPackage(fieldNameWithAnyCheck);
        return convertType(withPackage);
    }

    private String getFieldNameWithAnyCheck(DMNType fieldDMNType) {
        String name = fieldDMNType.getName();
        if ("Any".equals(name)) {
            return OBJECT_TYPE;
        } else {
            return CodegenStringUtil.escapeIdentifier(name);
        }
    }

    // This returns the generic type i.e. when Collection<String> then String
    private String fieldTypeUnwrapped() {
        if (fieldDMNType.isCollection()) {
            String typeName = getBaseType(fieldDMNType);
            return withPackage(typeName);
        }
        return fieldTypeWithPackage();
    }

    private String withPackage(String typeName) {
        String typeNameUpperCase = StringUtils.ucFirst(typeName);
        Optional<DMNTypeSafePackageName> dmnTypeSafePackageName = index.namespaceOfClass(typeName);
        return dmnTypeSafePackageName.map(p -> p.appendPackage(typeNameUpperCase)).orElse(typeNameUpperCase);
    }

    public String getBaseType(DMNType fieldType) {
        Optional<DMNType> baseType = getRecursiveBaseType(fieldType);
        return baseType.map(this::getFieldNameWithAnyCheck).orElse(OBJECT_TYPE);
    }

    private Optional<DMNType> getRecursiveBaseType(DMNType fieldType) {
        if (index.isIndexedClass(getFieldNameWithAnyCheck(fieldType))) {
            return Optional.of(fieldType);
        } else if (fieldType.getBaseType() == null) {
            return Optional.of(fieldType);
        } else {
            return getRecursiveBaseType(fieldType.getBaseType());
        }
    }

    private String convertType(String originalType) {
        Class<?> convertedClass;
        switch (originalType) {
            case "Any":
                convertedClass = Object.class;
                break;
            case "Date":
                convertedClass = LocalDate.class;
                break;
            case "Time":
                convertedClass = LocalTime.class;
                break;
            case "DateTime":
                convertedClass = LocalDateTime.class;
                break;
            case "DayTimeDuration":
            case "Days_32and_32time_32duration":
                convertedClass = Duration.class;
                break;
            case "YearMonthDuration":
                convertedClass = Period.class;
                break;
            default:
                convertedClass = null;
        }
        return convertedClass != null ? convertedClass.getCanonicalName() : originalType;
    }

    @Override
    public String getInitExpr() {
        return null;
    }

    @Override
    public List<AnnotationDefinition> getterAnnotations() {
        SimpleAnnotationDefinition annotation = new SimpleAnnotationDefinition("org.kie.dmn.feel.lang.FEELProperty");
        annotation.getValueMap().put("value", "\"" + originalMapKey + "\"");
        return Collections.singletonList(annotation);
    }

    @Override
    public boolean isKeyField() {
        return false;
    }

    @Override
    public boolean createAccessors() {
        return true;
    }

    @Override
    public boolean isStatic() {
        return false;
    }

    @Override
    public boolean isFinal() {
        return false;
    }

    public BlockStmt createFromMapEntry(BlockStmt simplePropertyBlock,
                                        BlockStmt pojoPropertyBlock,
                                        BlockStmt collectionsPropertyBlock, BlockStmt collectionsBasic) {
        if (fieldDMNType.isCollection() && fieldIsBasic()) {
            return replaceTemplate(collectionsBasic, fieldTypeUnwrapped());
        } else if (fieldDMNType.isCollection() && fieldIsDifferentThanObject()) {
            return replaceTemplate(collectionsPropertyBlock, fieldTypeUnwrapped());
        } else if (fieldDMNType.isComposite()) {
            return replaceTemplate(pojoPropertyBlock, fieldTypeWithPackage());
        } else if (fieldIsDifferentThanObject()) {
            return replaceTemplate(simplePropertyBlock, fieldTypeWithPackage());
        } else {
            return new BlockStmt();
        }
    }

    private boolean fieldIsBasic() {
        return fieldDMNType.getBaseType() != null && !fieldDMNType.getBaseType().isComposite();
    }

    private boolean fieldIsDifferentThanObject() {
        return !fieldTypeUnwrapped().equals(OBJECT_TYPE);
    }

    private BlockStmt replaceTemplate(BlockStmt pojoPropertyBlock, String objectType) {
        BlockStmt clone = pojoPropertyBlock.clone();
        clone.removeComment();

        clone.findAll(NameExpr.class, this::propertyPlaceHolder)
                .forEach(n -> n.replace(new NameExpr(fieldName)));

        clone.findAll(StringLiteralExpr.class, this::propertyPlaceHolder)
                .forEach(n -> n.replace(new StringLiteralExpr(originalMapKey)));

        clone.findAll(ClassOrInterfaceType.class, this::propertyTypePlaceHolder)
                .forEach(n -> n.replace(parseClassOrInterfaceType(objectType)));

        return clone;
    }

    private boolean propertyPlaceHolder(NameExpr n) {
        return n.toString().equals("$property$");
    }

    private boolean propertyPlaceHolder(StringLiteralExpr n) {
        return n.asString().equals("$property$");
    }

    private boolean propertyTypePlaceHolder(Object n) {
        return n.toString().equals("PropertyType");
    }
}
