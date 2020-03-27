/*
 * Licensed to Crate under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.  Crate licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial
 * agreement.
 */

package io.crate.metadata;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import io.crate.common.collections.Lists2;
import io.crate.expression.symbol.FuncArg;
import io.crate.metadata.functions.Signature;
import io.crate.metadata.functions.SignatureBinder;
import io.crate.metadata.functions.params.FuncParams;
import io.crate.types.DataType;
import io.crate.types.TypeSignature;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.inject.Inject;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

public class Functions {

    private final Map<FunctionName, FunctionResolver> functionResolvers;
    private final Map<FunctionName, List<FuncResolver>> udfFunctionImplementations = new ConcurrentHashMap<>();
    private final Map<FunctionName, List<FuncResolver>> functionImplementations;

    @Inject
    public Functions(Map<FunctionIdent, FunctionImplementation> functionImplementations,
                     Map<FunctionName, FunctionResolver> functionResolvers,
                     Map<FunctionName, List<FuncResolver>> functionImplementationsBySignature) {
        this.functionResolvers = Maps.newHashMap(functionResolvers);
        this.functionResolvers.putAll(generateFunctionResolvers(functionImplementations));
        this.functionImplementations = functionImplementationsBySignature;
    }

    public Functions(Map<FunctionIdent, FunctionImplementation> functionImplementations,
                     Map<FunctionName, FunctionResolver> functionResolvers) {
        this(functionImplementations, functionResolvers, Collections.emptyMap());
    }

    public Map<FunctionName, FunctionResolver> functionResolvers() {
        return functionResolvers;
    }

    public Map<FunctionName, List<FuncResolver>> udfFunctionResolvers() {
        return udfFunctionImplementations;
    }

    private Map<FunctionName, FunctionResolver> generateFunctionResolvers(Map<FunctionIdent, FunctionImplementation> functionImplementations) {
        Multimap<FunctionName, Tuple<FunctionIdent, FunctionImplementation>> signatures = getSignatures(functionImplementations);
        return signatures.keys().stream()
            .distinct()
            .collect(Collectors.toMap(name -> name, name -> new GeneratedFunctionResolver(signatures.get(name))));
    }

    /**
     * Adds all provided {@link FunctionIdent} to a Multimap with the function
     * name as key and all possible overloads as values.
     * @param functionImplementations A map of all {@link FunctionIdent}.
     * @return The MultiMap with the function name as key and a tuple of
     *         FunctionIdent and FunctionImplementation as value.
     */
    private Multimap<FunctionName, Tuple<FunctionIdent, FunctionImplementation>> getSignatures(
        Map<FunctionIdent, FunctionImplementation> functionImplementations) {
        Multimap<FunctionName, Tuple<FunctionIdent, FunctionImplementation>> signatureMap = ArrayListMultimap.create();
        for (Map.Entry<FunctionIdent, FunctionImplementation> entry : functionImplementations.entrySet()) {
            signatureMap.put(entry.getKey().fqnName(), new Tuple<>(entry.getKey(), entry.getValue()));
        }
        return signatureMap;
    }

    public void registerUdfFunctionImplementationsForSchema(
        String schema, Map<FunctionName, List<FuncResolver>> functions) {
        // remove deleted ones before re-registering all current ones for the given schema
        udfFunctionImplementations.entrySet()
            .removeIf(
                function ->
                    schema.equals(function.getKey().schema())
                    && functions.get(function.getKey()) == null);
        udfFunctionImplementations.putAll(functions);
    }

    public void deregisterUdfResolversForSchema(String schema) {
        udfFunctionImplementations.keySet()
            .removeIf(function -> schema.equals(function.schema()));
    }

    /**
     * Return a function that matches the name/arguments.
     *
     * <pre>
     * {@code
     * Lookup logic:
     *     No schema:   Built-ins -> Function or UDFs in searchPath
     *     With Schema: Function or UDFs in schema
     * }
     * </pre>
     *
     * @throws UnsupportedOperationException if the function wasn't found
     */
    public FunctionImplementation get(@Nullable String suppliedSchema,
                                      String functionName,
                                      List<? extends FuncArg> arguments,
                                      SearchPath searchPath) {
        FunctionName fqnName = new FunctionName(suppliedSchema, functionName);
        FunctionImplementation func = getBuiltinByArgs(fqnName, arguments, searchPath);
        if (func == null) {
            func = resolveUserDefinedByArgs(fqnName, arguments, searchPath);
        }
        if (func == null) {
            throw raiseUnknownFunction(suppliedSchema, functionName, arguments);
        }
        return func;
    }

    @Nullable
    private static FunctionImplementation resolveFunctionForArgumentTypes(List<? extends FuncArg> types,
                                                                          FunctionResolver resolver) {
        List<DataType> signature = resolver.getSignature(types);
        if (signature != null) {
            return resolver.getForTypes(signature);
        }
        return null;
    }

    /**
     * Returns the built-in function implementation for the given function name and arguments.
     *
     * @param functionName The full qualified function name.
     * @param dataTypes The function argument types.
     * @return a function implementation or null if it was not found.
     */
    @Nullable
    private FunctionImplementation getBuiltin(FunctionName functionName, List<DataType> dataTypes) {
        // Try new signature registry first
        FunctionImplementation impl = resolveFunctionBySignature(
            functionName,
            dataTypes,
            SearchPath.pathWithPGCatalogAndDoc(),
            functionImplementations::get
        );
        if (impl != null) {
            return impl;
        }

        FunctionResolver resolver = functionResolvers.get(functionName);
        if (resolver == null) {
            return null;
        }
        return resolver.getForTypes(dataTypes);
    }

    /**
     * Returns the built-in function implementation for the given function name and argument types.
     * The types may be cast to match the built-in argument types.
     *
     * @param functionName The full qualified function name.
     * @param argumentsTypes The function argument types.
     * @return a function implementation or null if it was not found.
     */
    @Nullable
    private FunctionImplementation getBuiltinByArgs(FunctionName functionName,
                                                    List<? extends FuncArg> argumentsTypes,
                                                    SearchPath searchPath) {
        // V2
        FunctionImplementation impl = resolveFunctionBySignature(
            functionName,
            Lists2.map(argumentsTypes, FuncArg::valueType),
            searchPath,
            functionImplementations::get
        );
        if (impl != null) {
            return impl;
        }

        FunctionResolver resolver = lookupFunctionResolver(functionName, searchPath, functionResolvers::get);
        if (resolver == null) {
            return null;
        }
        return resolveFunctionForArgumentTypes(argumentsTypes, resolver);
    }

    @Nullable
    private FunctionImplementation resolveFunctionBySignature(FunctionName name,
                                                              List<DataType> arguments,
                                                              SearchPath searchPath,
                                                              Function<FunctionName, List<FuncResolver>> lookupFunction) {
        var candidates = lookupFunction.apply(name);
        if (candidates == null && name.schema() == null) {
            for (String pathSchema : searchPath) {
                FunctionName searchPathFunctionName = new FunctionName(pathSchema, name.name());
                candidates = lookupFunction.apply(searchPathFunctionName);
                if (candidates != null) {
                    break;
                }
            }
        }
        if (candidates != null) {
            // First lets try exact candidates, no generic type variables, no coercion allowed.
            var exactCandidates = candidates.stream()
                .filter(function -> function.getSignature().getTypeVariableConstraints().isEmpty())
                .collect(Collectors.toList());
            var match = matchFunctionCandidates(exactCandidates, arguments, false);
            if (match != null) {
                return match;
            }

            // Second, try candidates with generic type variables, still no coercion allowed.
            var genericCandidates = candidates.stream()
                .filter(function -> !function.getSignature().getTypeVariableConstraints().isEmpty())
                .collect(Collectors.toList());
            match = matchFunctionCandidates(genericCandidates, arguments, false);
            if (match != null) {
                return match;
            }

            // Last, try all candidates which allow coercion.
            var candidatesAllowingCoercion = candidates.stream()
                .filter(function -> function.getSignature().isCoercionAllowed())
                .collect(Collectors.toList());
            return matchFunctionCandidates(candidatesAllowingCoercion, arguments, true);
        }
        return null;
    }

    @Nullable
    private static FunctionImplementation matchFunctionCandidates(List<FuncResolver> candidates,
                                                                  List<DataType> argumentTypes,
                                                                  boolean allowCoercion) {
        for (FuncResolver candidate : candidates) {
            Signature boundSignature = new SignatureBinder(candidate.getSignature(), allowCoercion)
                .bind(argumentTypes);
            if (boundSignature != null) {
                return candidate.apply(Lists2.map(boundSignature.getArgumentTypes(), TypeSignature::createType));
            }
        }
        return null;
    }


    /**
     * Returns the user-defined function implementation for the given function name and argTypes.
     *
     * @param functionName The full qualified function name.
     * @param argTypes The function argTypes.
     * @return a function implementation.
     */
    @Nullable
    private FunctionImplementation getUserDefined(FunctionName functionName,
                                                  List<DataType> argTypes) throws UnsupportedOperationException {
        return resolveFunctionBySignature(
            functionName,
            argTypes,
            SearchPath.pathWithPGCatalogAndDoc(),
            udfFunctionImplementations::get
        );
    }

    /**
     * Returns the user-defined function implementation for the given function name and arguments.
     * The types may be cast to match the built-in argument types.
     *
     * @param functionName The full qualified function name.
     * @param argumentsTypes The function arguments.
     * @param searchPath The {@link SearchPath} against which to try to resolve the function if it is not identified by
     *                   a fully qualifed name (ie. `schema.functionName`)
     * @return a function implementation.
     */
    @Nullable
    private FunctionImplementation resolveUserDefinedByArgs(FunctionName functionName,
                                                            List<? extends FuncArg> argumentsTypes,
                                                            SearchPath searchPath) throws UnsupportedOperationException {
        return resolveFunctionBySignature(
            functionName,
            Lists2.map(argumentsTypes, FuncArg::valueType),
            searchPath,
            udfFunctionImplementations::get
        );
    }

    @Nullable
    private static FunctionResolver lookupFunctionResolver(FunctionName functionName,
                                                           Iterable<String> searchPath,
                                                           Function<FunctionName, FunctionResolver> lookupFunction) {
        FunctionResolver functionResolver = lookupFunction.apply(functionName);
        if (functionResolver == null && functionName.schema() == null) {
            for (String pathSchema : searchPath) {
                FunctionName searchPathfunctionName = new FunctionName(pathSchema, functionName.name());
                functionResolver = lookupFunction.apply(searchPathfunctionName);
                if (functionResolver != null) {
                    break;
                }
            }
        }
        return functionResolver;
    }

    /**
     * Returns the function implementation for the given function ident.
     * First look up function in built-ins then fallback to user-defined functions.
     *
     * @param ident The function ident.
     * @return The function implementation.
     * @throws UnsupportedOperationException if no implementation is found.
     */
    public FunctionImplementation getQualified(FunctionIdent ident) throws UnsupportedOperationException {
        FunctionImplementation impl = getBuiltin(ident.fqnName(), ident.argumentTypes());
        if (impl == null) {
            impl = getUserDefined(ident.fqnName(), ident.argumentTypes());
        }
        return impl;
    }

    private static UnsupportedOperationException raiseUnknownFunction(@Nullable String suppliedSchema,
                                                                      String name,
                                                                      List<? extends FuncArg> arguments) {
        StringJoiner joiner = new StringJoiner(", ");
        for (FuncArg arg : arguments) {
            joiner.add(arg.valueType().toString());
        }
        String prefix = suppliedSchema == null ? "" : suppliedSchema + '.';
        throw new UnsupportedOperationException("unknown function: " + prefix + name + '(' + joiner.toString() + ')');
    }

    private static class GeneratedFunctionResolver implements FunctionResolver {

        private final Map<Integer, FuncParams> allFuncParams;
        private final Map<List<DataType>, FunctionImplementation> functions;

        GeneratedFunctionResolver(Collection<Tuple<FunctionIdent, FunctionImplementation>> functionTuples) {
            functions = new HashMap<>(functionTuples.size());

            Map<Integer, FuncParams.Builder> funcParamsBuilders = new HashMap<>();

            for (Tuple<FunctionIdent, FunctionImplementation> functionTuple : functionTuples) {
                List<DataType> argumentTypes = functionTuple.v1().argumentTypes();
                functions.put(argumentTypes, functionTuple.v2());

                FuncParams.Builder funcParamsBuilder = funcParamsBuilders.get(argumentTypes.size());
                if (funcParamsBuilder == null) {
                    funcParamsBuilders.put(argumentTypes.size(), FuncParams.builder(argumentTypes));
                } else {
                    funcParamsBuilder.mergeWithTypes(argumentTypes);
                }
            }

            allFuncParams = new HashMap<>(funcParamsBuilders.size());
            funcParamsBuilders.forEach((numArgs, builder) -> allFuncParams.put(numArgs, builder.build()));
        }

        @Override
        public FunctionImplementation getForTypes(List<DataType> dataTypes) throws IllegalArgumentException {
            return functions.get(dataTypes);
        }

        @Nullable
        @Override
        public List<DataType> getSignature(List<? extends FuncArg> funcArgs) {
            FuncParams funcParams = allFuncParams.get(funcArgs.size());
            if (funcParams != null) {
                List<DataType> sig = funcParams.match(funcArgs);
                if (sig != null) {
                    return sig;
                }
            }
            return null;
        }
    }
}
