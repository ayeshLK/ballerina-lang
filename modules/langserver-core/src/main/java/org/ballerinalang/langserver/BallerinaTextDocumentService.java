/*
 * Copyright (c) 2017, WSO2 Inc. (http://wso2.com) All Rights Reserved.
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
package org.ballerinalang.langserver;

import org.ballerinalang.langserver.completions.CompletionKeys;
import org.ballerinalang.langserver.completions.TreeVisitor;
import org.ballerinalang.langserver.completions.consts.CompletionItemResolver;
import org.ballerinalang.langserver.completions.resolvers.TopLevelResolver;
import org.ballerinalang.langserver.hover.HoverKeys;
import org.ballerinalang.langserver.hover.HoverTreeVisitor;
import org.ballerinalang.langserver.hover.constants.HoverConstants;
import org.ballerinalang.langserver.hover.util.HoverUtil;
import org.ballerinalang.langserver.signature.SignatureHelpUtil;
import org.ballerinalang.langserver.workspace.WorkspaceDocumentManager;
import org.ballerinalang.langserver.workspace.WorkspaceDocumentManagerImpl;
import org.ballerinalang.langserver.workspace.repository.WorkspacePackageRepository;
import org.ballerinalang.repository.PackageRepository;
import org.ballerinalang.util.diagnostic.DiagnosticListener;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.CodeLensParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.DocumentHighlight;
import org.eclipse.lsp4j.DocumentOnTypeFormattingParams;
import org.eclipse.lsp4j.DocumentRangeFormattingParams;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TextDocumentPositionParams;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.ballerinalang.compiler.Compiler;
import org.wso2.ballerinalang.compiler.tree.BLangNode;
import org.wso2.ballerinalang.compiler.tree.BLangPackage;
import org.wso2.ballerinalang.compiler.util.CompilerContext;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Text document service implementation for ballerina.
 */
public class BallerinaTextDocumentService implements TextDocumentService {

    private final BallerinaLanguageServer ballerinaLanguageServer;
    private final WorkspaceDocumentManager documentManager;
    private static final Logger LOGGER = LoggerFactory.getLogger(BallerinaTextDocumentService.class);
    private Map<String, List<Diagnostic>> lastDiagnosticMap;
    private BLangPackage builtinPkg;

    public BallerinaTextDocumentService(BallerinaLanguageServer ballerinaLanguageServer) {
        this.ballerinaLanguageServer = ballerinaLanguageServer;
        this.documentManager = new WorkspaceDocumentManagerImpl();
        this.lastDiagnosticMap = new HashMap<>();
        builtinPkg = BuiltinPackageLoader.getBuiltinPackage();
    }

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>>
    completion(TextDocumentPositionParams position) {
        return CompletableFuture.supplyAsync(() -> {
            List<CompletionItem> completions;
            TextDocumentServiceContext completionContext = new TextDocumentServiceContext();
            completionContext.put(DocumentServiceKeys.POSITION_KEY, position);
            BLangPackage bLangPackage = TextDocumentServiceUtil.getBLangPackage(completionContext, documentManager);
            // Visit the package to resolve the symbols
            TreeVisitor treeVisitor = new TreeVisitor(completionContext);
            bLangPackage.accept(treeVisitor);

            BLangNode symbolEnvNode = completionContext.get(CompletionKeys.SYMBOL_ENV_NODE_KEY);
            if (symbolEnvNode == null) {
                completions = CompletionItemResolver.getResolverByClass(TopLevelResolver.class)
                        .resolveItems(completionContext);
            } else {
                completions = CompletionItemResolver.getResolverByClass(symbolEnvNode.getClass())
                        .resolveItems(completionContext);
            }
            return Either.forLeft(completions);
        });
    }

    @Override
    public CompletableFuture<CompletionItem> resolveCompletionItem(CompletionItem unresolved) {
        return null;
    }

    @Override
    public CompletableFuture<Hover> hover(TextDocumentPositionParams position) {
        return CompletableFuture.supplyAsync(() -> {
            TextDocumentServiceContext hoverContext = new TextDocumentServiceContext();
            HoverUtil hoverUtil = new HoverUtil();
            hoverContext.put(DocumentServiceKeys.POSITION_KEY, position);
            BLangPackage bLangPackage = TextDocumentServiceUtil.getBLangPackage(hoverContext, documentManager);
            HoverTreeVisitor hoverTreeVisitor = new HoverTreeVisitor(hoverContext);
            bLangPackage.accept(hoverTreeVisitor);
            Hover hover;
            if (hoverContext.get(HoverKeys.PACKAGE_OF_HOVER_NODE_KEY) != null
                    && hoverContext.get(HoverKeys.PACKAGE_OF_HOVER_NODE_KEY).name.getValue()
                    .equals(HoverConstants.BUILT_IN_PACKAGE)) {
                BLangPackage packages = hoverUtil
                        .getBuiltInPackage(hoverContext.get(DocumentServiceKeys.COMPILER_CONTEXT_KEY),
                                hoverContext.get(HoverKeys.PACKAGE_OF_HOVER_NODE_KEY).name);
                hover = hoverUtil.resolveBuiltInPackageDoc(packages, hoverContext);
            } else {
                BLangPackage packages = hoverUtil
                        .getNativePackage(hoverContext.get(DocumentServiceKeys.COMPILER_CONTEXT_KEY),
                                hoverContext.get(HoverKeys.PACKAGE_OF_HOVER_NODE_KEY).name);
                hover = hoverUtil.resolveBuiltInPackageDoc(packages, hoverContext);
            }
            return hover;
        });
    }

    @Override
    public CompletableFuture<SignatureHelp> signatureHelp(TextDocumentPositionParams position) {
        return CompletableFuture.supplyAsync(() -> {
            String uri = position.getTextDocument().getUri();
            String fileContent = this.documentManager.getFileContent(Paths.get(URI.create(uri)));
            String callableItemName = SignatureHelpUtil.getCallableItemName(position.getPosition(), fileContent);
            TextDocumentServiceContext signatureContext = new TextDocumentServiceContext();
            signatureContext.put(DocumentServiceKeys.POSITION_KEY, position);
            BLangPackage bLangPackage = TextDocumentServiceUtil.getBLangPackage(signatureContext, documentManager);
            SignatureHelpUtil.BLangPackageWrapper pkgContext =
                    new SignatureHelpUtil.BLangPackageWrapper(builtinPkg, bLangPackage);
            return SignatureHelpUtil.getFunctionSignatureHelp(callableItemName, pkgContext);
        });
    }

    @Override
    public CompletableFuture<List<? extends Location>> definition(TextDocumentPositionParams position) {
        return CompletableFuture.supplyAsync(() -> null);
    }

    @Override
    public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
        return CompletableFuture.supplyAsync(() -> null);
    }

    @Override
    public CompletableFuture<List<? extends DocumentHighlight>> documentHighlight(
            TextDocumentPositionParams position) {
        return null;
    }

    @Override
    public CompletableFuture<List<? extends SymbolInformation>> documentSymbol(DocumentSymbolParams params) {
        return CompletableFuture.supplyAsync(() -> null);
    }

    @Override
    public CompletableFuture<List<? extends Command>> codeAction(CodeActionParams params) {
        return CompletableFuture.supplyAsync(() ->
            params.getContext().getDiagnostics().stream()
            .map(diagnostic -> {
                List<Command> res = new ArrayList<>();
                return res.stream();
            })
            .flatMap(it -> it)
            .collect(Collectors.toList())
        );
    }

    @Override
    public CompletableFuture<List<? extends CodeLens>> codeLens(CodeLensParams params) {
        return null;
    }

    @Override
    public CompletableFuture<CodeLens> resolveCodeLens(CodeLens unresolved) {
        return null;
    }

    @Override
    public CompletableFuture<List<? extends TextEdit>> formatting(DocumentFormattingParams params) {
        return null;
    }

    @Override
    public CompletableFuture<List<? extends TextEdit>> rangeFormatting(DocumentRangeFormattingParams params) {
        return null;
    }

    @Override
    public CompletableFuture<List<? extends TextEdit>> onTypeFormatting(DocumentOnTypeFormattingParams params) {
        return null;
    }

    @Override
    public CompletableFuture<WorkspaceEdit> rename(RenameParams params) {
        return null;
    }

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        Path openedPath = this.getPath(params.getTextDocument().getUri());
        if (openedPath == null) {
            return;
        }

        String content = params.getTextDocument().getText();
        this.documentManager.openFile(openedPath, content);

        compileAndSendDiagnostics(content, openedPath);
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        Path changedPath = this.getPath(params.getTextDocument().getUri());
        if (changedPath == null) {
            return;
        }

        String content = params.getContentChanges().get(0).getText();
        this.documentManager.updateFile(changedPath, content);

        compileAndSendDiagnostics(content, changedPath);
    }

    private void compileAndSendDiagnostics(String content, Path path) {
        String pkgName = TextDocumentServiceUtil.getPackageFromContent(content);
        String sourceRoot = TextDocumentServiceUtil.getSourceRoot(path, pkgName);

        PackageRepository packageRepository = new WorkspacePackageRepository(sourceRoot, documentManager);
        CompilerContext context = TextDocumentServiceUtil.prepareCompilerContext(packageRepository, sourceRoot);

        List<org.ballerinalang.util.diagnostic.Diagnostic> balDiagnostics = new ArrayList<>();
        CollectDiagnosticListener diagnosticListener = new CollectDiagnosticListener(balDiagnostics);
        context.put(DiagnosticListener.class, diagnosticListener);

        Compiler compiler = Compiler.getInstance(context);
        if ("".equals(pkgName)) {
            Path filePath = path.getFileName();
            if (filePath != null) {
                compiler.compile(filePath.toString());
            }
        } else {
            compiler.compile(pkgName);
        }

        publishDiagnostics(balDiagnostics, path);
    }

    private void publishDiagnostics(List<org.ballerinalang.util.diagnostic.Diagnostic> balDiagnostics, Path path) {
        Map<String, List<Diagnostic>> diagnosticsMap = new HashMap<String, List<Diagnostic>>();
        balDiagnostics.forEach(diagnostic -> {
            Diagnostic d = new Diagnostic();
            d.setSeverity(DiagnosticSeverity.Error);
            d.setMessage(diagnostic.getMessage());
            Range r = new Range();

            int startLine = diagnostic.getPosition().getStartLine() - 1; // LSP diagnostics range is 0 based
            int startChar = diagnostic.getPosition().getStartColumn() - 1;
            int endLine = diagnostic.getPosition().getEndLine() - 1;
            int endChar = diagnostic.getPosition().getEndColumn() - 1;

            if (endLine <= 0) {
                endLine = startLine;
            }

            if (endChar <= 0) {
                endChar = startChar + 1;
            }

            r.setStart(new Position(startLine, startChar));
            r.setEnd(new Position(endLine, endChar));
            d.setRange(r);


            String fileName = diagnostic.getPosition().getSource().getCompilationUnitName();
            Path filePath = Paths.get(path.getParent().toString(), fileName);
            String fileURI = filePath.toUri().toString();

            if (!diagnosticsMap.containsKey(fileURI)) {
                diagnosticsMap.put(fileURI, new ArrayList<Diagnostic>());
            }
            List<Diagnostic> clientDiagnostics = diagnosticsMap.get(fileURI);

            clientDiagnostics.add(d);
        });

        // clear previous diagnostics
        List<Diagnostic> empty = new ArrayList<Diagnostic>(0);
        for (Map.Entry<String, List<Diagnostic>> entry : lastDiagnosticMap.entrySet()) {
            if (diagnosticsMap.containsKey(entry.getKey())) {
                continue;
            }
            PublishDiagnosticsParams diagnostics = new PublishDiagnosticsParams();
            diagnostics.setUri(entry.getKey());
            diagnostics.setDiagnostics(empty);
            this.ballerinaLanguageServer.getClient().publishDiagnostics(diagnostics);
        }

        for (Map.Entry<String, List<Diagnostic>> entry : diagnosticsMap.entrySet()) {
            PublishDiagnosticsParams diagnostics = new PublishDiagnosticsParams();
            diagnostics.setUri(entry.getKey());
            diagnostics.setDiagnostics(entry.getValue());
            this.ballerinaLanguageServer.getClient().publishDiagnostics(diagnostics);
        }

        lastDiagnosticMap = diagnosticsMap;
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        Path closedPath = this.getPath(params.getTextDocument().getUri());
        if (closedPath == null) {
            return;
        }

        this.documentManager.closeFile(this.getPath(params.getTextDocument().getUri()));
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
    }

    private Path getPath(String uri) {
        Path path = null;
        try {
            path = Paths.get(new URL(uri).toURI());
        } catch (URISyntaxException | MalformedURLException e) {
            LOGGER.error(e.getMessage());
        } finally {
            return path;
        }
    }
}
