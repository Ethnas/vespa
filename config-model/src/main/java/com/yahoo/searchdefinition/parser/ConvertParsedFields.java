// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.searchdefinition.parser;

import com.yahoo.document.DataType;
import com.yahoo.document.DataTypeName;
import com.yahoo.searchdefinition.parser.ConvertParsedTypes.TypeResolver;
import com.yahoo.searchdefinition.Index;
import com.yahoo.searchdefinition.Schema;
import com.yahoo.searchdefinition.document.Attribute;
import com.yahoo.searchdefinition.document.BooleanIndexDefinition;
import com.yahoo.searchdefinition.document.Case;
import com.yahoo.searchdefinition.document.Dictionary;
import com.yahoo.searchdefinition.document.NormalizeLevel;
import com.yahoo.searchdefinition.document.RankType;
import com.yahoo.searchdefinition.document.SDDocumentType;
import com.yahoo.searchdefinition.document.SDField;
import com.yahoo.searchdefinition.document.Sorting;
import com.yahoo.vespa.documentmodel.SummaryField;
import com.yahoo.vespa.documentmodel.SummaryTransform;

import java.util.Locale;

/**
 * Helper for converting ParsedField etc to SDField with settings
 *
 * @author arnej27959
 **/
public class ConvertParsedFields {

    private final TypeResolver context;
    
    ConvertParsedFields(TypeResolver context) {
        this.context = context;
    }

    static void convertMatchSettings(SDField field, ParsedMatchSettings parsed) {
        parsed.getMatchType().ifPresent(matchingType -> field.setMatchingType(matchingType));
        parsed.getMatchCase().ifPresent(casing -> field.setMatchingCase(casing));
        parsed.getGramSize().ifPresent(gramSize -> field.getMatching().setGramSize(gramSize));
        parsed.getMaxLength().ifPresent(maxLength -> field.getMatching().maxLength(maxLength));
        parsed.getMatchAlgorithm().ifPresent
            (matchingAlgorithm -> field.setMatchingAlgorithm(matchingAlgorithm));
        parsed.getExactTerminator().ifPresent
            (exactMatchTerminator -> field.getMatching().setExactMatchTerminator(exactMatchTerminator));
    }

    void convertSorting(SDField field, ParsedSorting parsed, String name) {
        Attribute attribute = field.getAttributes().get(name);
        if (attribute == null) {
            attribute = new Attribute(name, field.getDataType());
            field.addAttribute(attribute);
        }
        Sorting sorting = attribute.getSorting();
        if (parsed.getAscending()) {
            sorting.setAscending();
        } else {
            sorting.setDescending();
        }
        parsed.getFunction().ifPresent(function -> sorting.setFunction(function));
        parsed.getStrength().ifPresent(strength -> sorting.setStrength(strength));
        parsed.getLocale().ifPresent(locale -> sorting.setLocale(locale));
    }

    void convertAttribute(SDField field, ParsedAttribute parsed) {
        String name = parsed.name();
        String fieldName = field.getName();
        Attribute attribute = null;
        if (fieldName.endsWith("." + name)) {
            attribute = field.getAttributes().get(field.getName());
        }
        if (attribute == null) {
            attribute = field.getAttributes().get(name);
            if (attribute == null) {
                attribute = new Attribute(name, field.getDataType());
                field.addAttribute(attribute);
            }
        }
        attribute.setHuge(parsed.getHuge());
        attribute.setPaged(parsed.getPaged());
        attribute.setFastSearch(parsed.getFastSearch());
        attribute.setFastAccess(parsed.getFastAccess());
        attribute.setMutable(parsed.getMutable());
        attribute.setEnableBitVectors(parsed.getEnableBitVectors());
        attribute.setEnableOnlyBitVector(parsed.getEnableOnlyBitVector());

        // attribute.setTensorType(?)

        for (String alias : parsed.getAliases()) {
            field.getAliasToName().put(alias, parsed.lookupAliasedFrom(alias));
        }
        var distanceMetric = parsed.getDistanceMetric();
        if (distanceMetric.isPresent()) {
            String upper = distanceMetric.get().toUpperCase(Locale.ENGLISH);
            attribute.setDistanceMetric(Attribute.DistanceMetric.valueOf(upper));
        }
        var sorting = parsed.getSorting();
        if (sorting.isPresent()) {
            convertSorting(field, sorting.get(), name);
        }
    }

    private void convertRankType(SDField field, String indexName, String rankType) {
        RankType type = RankType.fromString(rankType);
        if (indexName == null || indexName.equals("")) {
            field.setRankType(type); // Set default if the index is not specified.
        } else {
            Index index = field.getIndex(indexName);
            if (index == null) {
                index = new Index(indexName);
                field.addIndex(index);
            }
            index.setRankType(type);
        }
    }

    private void convertNormalizing(SDField field, String setting) {
        NormalizeLevel.Level level;
        if ("none".equals(setting)) {
            level = NormalizeLevel.Level.NONE;
        } else if ("codepoint".equals(setting)) {
            level = NormalizeLevel.Level.CODEPOINT;
        } else if ("lowercase".equals(setting)) {
            level = NormalizeLevel.Level.LOWERCASE;
        } else if ("accent".equals(setting)) {
            level = NormalizeLevel.Level.ACCENT;
        } else if ("all".equals(setting)) {
            level = NormalizeLevel.Level.ACCENT;
        } else {
            throw new IllegalArgumentException("invalid normalizing setting: " + setting);
        }
        field.setNormalizing(new NormalizeLevel(level, true));
    }

    // from grammar, things that can be inside struct-field block
    private void convertCommonFieldSettings(SDField field, ParsedField parsed) {
        convertMatchSettings(field, parsed.matchSettings());
        var indexing = parsed.getIndexing();
        if (indexing.isPresent()) {
            field.setIndexingScript(indexing.get().script());
        }
        parsed.getStemming().ifPresent(value -> field.setStemming(value));
        parsed.getNormalizing().ifPresent(value -> convertNormalizing(field, value));
        for (var attribute : parsed.getAttributes()) {
            convertAttribute(field, attribute);
        }
        for (var summaryField : parsed.getSummaryFields()) {
            var dataType = field.getDataType();
            var otherType = summaryField.getType();
            if (otherType != null) {
                dataType = context.resolveType(otherType);
            }
            convertSummaryField(field, summaryField, dataType);
        }
        for (String command : parsed.getQueryCommands()) {
            field.addQueryCommand(command);
        }
        for (var structField : parsed.getStructFields()) {
            convertStructField(field, structField);
        }
    }

    private void convertStructField(SDField field, ParsedField parsed) {
        SDField structField = field.getStructField(parsed.name());
        if (structField == null ) {
            throw new IllegalArgumentException("Struct field '" + parsed.name() + "' has not been defined in struct " +
                                               "for field '" + field.getName() + "'.");
        }
        convertCommonFieldSettings(structField, parsed);
    }

    private void convertExtraFieldSettings(SDField field, ParsedField parsed) {
        String name = parsed.name();
        for (var dictOp : parsed.getDictionaryOptions()) {
            var dictionary = field.getOrSetDictionary();
            switch (dictOp) {
            case HASH:    dictionary.updateType(Dictionary.Type.HASH); break;
            case BTREE:   dictionary.updateType(Dictionary.Type.BTREE); break;
            case CASED:   dictionary.updateMatch(Case.CASED); break;
            case UNCASED: dictionary.updateMatch(Case.UNCASED); break;
            }
        }
        for (var index : parsed.getIndexes()) {
            convertIndex(field, index);
        }
        for (var alias : parsed.getAliases()) {
            field.getAliasToName().put(alias, parsed.lookupAliasedFrom(alias));
        }
        parsed.getRankTypes().forEach((indexName, rankType) -> convertRankType(field, indexName, rankType));
        parsed.getSorting().ifPresent(sortInfo -> convertSorting(field, sortInfo, name));
        if (parsed.getBolding()) {
            // TODO must it be so ugly:
            SummaryField summaryField = field.getSummaryField(name, true);
            summaryField.addSource(name);
            summaryField.addDestination("default");
            summaryField.setTransform(summaryField.getTransform().bold());
        }
        if (parsed.getLiteral()) {
            field.getRanking().setLiteral(true);
        }
        if (parsed.getFilter()) {
            field.getRanking().setFilter(true);
        }
    }

    static void convertSummaryFieldSettings(SummaryField summary, ParsedSummaryField parsed) {
        var transform = SummaryTransform.NONE;
        if (parsed.getMatchedElementsOnly()) {
            transform = SummaryTransform.MATCHED_ELEMENTS_FILTER;
        } else if (parsed.getDynamic()) {
            transform = SummaryTransform.DYNAMICTEASER;
        }
        if (parsed.getBolded()) {
            transform = transform.bold();
        }
        summary.setTransform(transform);
        for (String source : parsed.getSources()) {
            summary.addSource(source);
        }
        for (String destination : parsed.getDestinations()) {
            summary.addDestination(destination);
        }
        summary.setImplicit(false);
    }

    private void convertSummaryField(SDField field, ParsedSummaryField parsed, DataType type) {
        var summary = new SummaryField(parsed.name(), type);
        convertSummaryFieldSettings(summary, parsed);
        summary.addDestination("default");
        if (parsed.getSources().isEmpty()) {
            summary.addSource(field.getName());
        }
        field.addSummaryField(summary);
    }

    private void convertIndex(SDField field, ParsedIndex parsed) {
        String indexName = parsed.name();
        Index index = field.getIndex(indexName);
        if (index == null) {
            index = new Index(indexName);
            field.addIndex(index);
        }
        convertIndexSettings(index, parsed);
    }

    private void convertIndexSettings(Index index, ParsedIndex parsed) {
        parsed.getPrefix().ifPresent(prefix -> index.setPrefix(prefix));
        for (String alias : parsed.getAliases()) {
            index.addAlias(alias);
        }
        parsed.getStemming().ifPresent(stemming -> index.setStemming(stemming));
        var arity = parsed.getArity();
        var lowerBound = parsed.getLowerBound();
        var upperBound = parsed.getUpperBound();
        var densePostingListThreshold = parsed.getDensePostingListThreshold();
        if (arity.isPresent() || 
            lowerBound.isPresent() ||
            upperBound.isPresent() ||
            densePostingListThreshold.isPresent())
        {
            var bid = new BooleanIndexDefinition(arity, lowerBound, upperBound, densePostingListThreshold);
            index.setBooleanIndexDefiniton(bid);
        }
        parsed.getEnableBm25().ifPresent(enableBm25 -> index.setInterleavedFeatures(enableBm25));
        parsed.getHnswIndexParams().ifPresent
            (hnswIndexParams -> index.setHnswIndexParams(hnswIndexParams));
    }

    SDField convertDocumentField(Schema schema, SDDocumentType document, ParsedField parsed) {
        String name = parsed.name();
        DataType dataType = context.resolveType(parsed.getType());
        var field = new SDField(document, name, dataType);
        convertCommonFieldSettings(field, parsed);
        convertExtraFieldSettings(field, parsed);
        document.addField(field);
        return field;
    }

    void convertExtraField(Schema schema, ParsedField parsed) {
        String name = parsed.name();
        DataType dataType = context.resolveType(parsed.getType());
        var field = new SDField(schema.getDocument(), name, dataType);
        convertCommonFieldSettings(field, parsed);
        convertExtraFieldSettings(field, parsed);
        schema.addExtraField(field);
    }

    void convertExtraIndex(Schema schema, ParsedIndex parsed) {
        Index index = new Index(parsed.name());
        convertIndexSettings(index, parsed);
        schema.addIndex(index);
    }

    void convertStructDeclaration(Schema schema, SDDocumentType document, ParsedStruct parsed) {
        // TODO - can we cleanup this mess
        var structProxy = new SDDocumentType(parsed.name(), schema);
        structProxy.setStruct(context.resolveStruct(parsed));
        for (var structField : parsed.getFields()) {
            var fieldType = context.resolveType(structField.getType());
            var field = new SDField(structProxy, structField.name(), fieldType);
            convertCommonFieldSettings(field, structField);
            structProxy.addField(field);
        }
        for (String inherit : parsed.getInherited()) {
            structProxy.inherit(new DataTypeName(inherit));                
        }
        document.addType(structProxy);
    }

}