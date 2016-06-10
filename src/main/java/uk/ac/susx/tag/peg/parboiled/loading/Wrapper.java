package uk.ac.susx.tag.peg.parboiled.loading;

import org.parboiled.BaseParser;
import org.parboiled.Parboiled;
import org.parboiled.Rule;
import org.parboiled.common.ImmutableList;
import org.parboiled.errors.ErrorUtils;
import org.parboiled.parserunners.ReportingParseRunner;
import org.parboiled.support.ParseTreeUtils;
import org.parboiled.support.ParsingResult;
import uk.ac.susx.tag.peg.parboiled.AstToJava;
import uk.ac.susx.tag.peg.parboiled.GrammarException;
import uk.ac.susx.tag.peg.parboiled.PegParser;

import javax.tools.*;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by simon on 09/06/16.
 */
public class Wrapper implements AutoCloseable {


    private final String grammar;
    private String grammarName;
    private String entryPoint;
    private final Path outPath;
    private final Path grammarPath;
    private final Path classesPath;
    private final String pkg;

    private BaseParser<?> parser;
    private Method entryPointMethod;

    public Wrapper(String grammar) {
        this("", grammar);
    }

    public Wrapper(String pkg, String grammar)  {
        this.grammar = grammar;

        this.pkg = pkg;

        Matcher match = Pattern.compile("(?m)(\\w+)\\s*<-.+").matcher(grammar);

        if (!match.find()) {
            throw new GrammarException("can't find entry point $grammar");
        }

        entryPoint = grammarName = match.group(1);

        outPath = Paths.get("target/generated-sources/parboiled").resolve(pkg.replaceAll("\\.", "/"));
        classesPath = Paths.get("target/classes").resolve(pkg.replaceAll("\\.", "/"));

        grammarPath = outPath.resolve(grammarName + ".java");

        try {

            Files.createDirectories(classesPath);
            Files.createDirectories(outPath);
        } catch (IOException e) {
            throw new GrammarException(e);
        }

        generateCode();
        compile();
        load();
    }

    void generateCode() {

        PegParser parser  = Parboiled.createParser(PegParser.class);

        AstToJava astToJava = new AstToJava(grammarName);

        String java = astToJava.toJava(parser.parse(grammar));

        try (
            BufferedWriter writer = Files.newBufferedWriter(grammarPath)
            ) {
            writer.write(java);
        } catch (IOException e) {
            throw new GrammarException(e);
        }

    }

    void compile() {

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

        StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, Locale.getDefault(), null);

        Iterable<? extends JavaFileObject> javaObjects = fileManager.getJavaFileObjectsFromStrings(
            ImmutableList.of(
                outPath.resolve(grammarName).toString() + ".java"
            )
        );

        String[] compileOptions = new String[]{"-d", classesPath.toFile().getAbsolutePath()};

        Iterable<String> compilationOptions = Arrays.asList(compileOptions);

        JavaCompiler.CompilationTask compilerTask = compiler.getTask(null, fileManager, diagnostics, compilationOptions, null, javaObjects);

        if (!compilerTask.call()) {
            String msg = "";
            for (Diagnostic<?> diagnostic : diagnostics.getDiagnostics()) {
                msg += String.format("Error on line %d in %s\n", diagnostic.getLineNumber(), diagnostic);
            }
            throw new GrammarException("Could not compile project");
        }
    }

    void load() {
        try {

            URLClassLoader classLoader = URLClassLoader.newInstance(new URL[]{ outPath.toFile().toURI().toURL() });

            Class<? extends BaseParser> parserClass = (Class<? extends BaseParser>)Class.forName(grammarName, true, classLoader);

            parserClass = (Class<? extends BaseParser> )new ClassReloader(classesPath).loadClass(parserClass.getName()).newInstance().getClass();

            parser = Parboiled.createParser(parserClass);

            entryPointMethod = parserClass.getMethod(entryPoint);

            classLoader.close();
        } catch(IOException | ClassNotFoundException | NoSuchMethodException | IllegalAccessException | InstantiationException e) {
            throw new GrammarException(e);
        }
    }



    public String parse(String input) {
        try {

            ReportingParseRunner runner = new ReportingParseRunner <>((Rule)entryPointMethod.invoke(parser));
            ParsingResult<?> result = runner.run(input);

            if (!result.parseErrors.isEmpty()) {
                throw new GrammarException(ErrorUtils.printParseError(result.parseErrors.get(0)));
            } else {
                return ParseTreeUtils.printNodeTree(result) + '\n';
            }

        } catch (IllegalAccessException | InvocationTargetException e) {

            throw new GrammarException(e);
        }

    }

    public static void main(String[] args) throws Exception {

        final String input = "aabbcc";

        // The grammar which echos the parsed characters to theconsole,
        // skipping any white space chars.
        final String grammar = "Test <- 'a'*'b'*'c'* $";


        try (
                Wrapper pw = new Wrapper("some.package",grammar);
        ) {

            System.out.printf(pw.parse(input));
        }


    }


    @Override
    public void close() throws Exception {
        Files.delete(grammarPath);
        Files.delete(classesPath.resolve(grammarName+".class"));
    }
}