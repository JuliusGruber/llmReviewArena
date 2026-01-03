package dev.reviewarena.io;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Map;

/**
 * Loads and renders prompt templates using Freemarker.
 *
 * <p>Templates are loaded from the classpath under {@code prompts/} directory.
 * Placeholders in templates use Freemarker's ${variableName} syntax.
 *
 * <p>Example usage:
 * <pre>{@code
 * TemplateLoader loader = new TemplateLoader();
 * TemplateContext context = TemplateContext.forTask();
 * String rendered = loader.render("task.md", context);
 * }</pre>
 */
public class TemplateLoader {

    private static final Logger log = LoggerFactory.getLogger(TemplateLoader.class);
    private static final String TEMPLATE_PATH = "prompts";

    private final Configuration freemarkerConfig;

    /**
     * Creates a new TemplateLoader with default Freemarker configuration.
     */
    public TemplateLoader() {
        freemarkerConfig = new Configuration(Configuration.VERSION_2_3_32);
        freemarkerConfig.setClassLoaderForTemplateLoading(
                getClass().getClassLoader(), TEMPLATE_PATH);
        freemarkerConfig.setDefaultEncoding("UTF-8");
        freemarkerConfig.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        freemarkerConfig.setLogTemplateExceptions(false);
        freemarkerConfig.setWrapUncheckedExceptions(true);
    }

    /**
     * Loads a template from the classpath and renders it with the given context.
     *
     * @param templateName the template file name (e.g., "task.md", "round-0.md")
     * @param context      the template context containing placeholder values
     * @return the rendered template content
     * @throws TemplateException if template loading or processing fails
     */
    public String render(String templateName, TemplateContext context) {
        return render(templateName, context.toDataModel());
    }

    /**
     * Renders a template with the given synthesis context.
     *
     * @param templateName the template file name (e.g., "final-synth.md")
     * @param context      the synthesis context with placeholder values
     * @return the rendered template content
     * @throws TemplateException if rendering fails
     */
    public String render(String templateName, SynthesisContext context) {
        return render(templateName, context.toDataModel());
    }

    /**
     * Renders a template with a raw data model map.
     *
     * @param templateName the template file name
     * @param dataModel    the placeholder values
     * @return the rendered template content
     * @throws TemplateException if rendering fails
     */
    public String render(String templateName, Map<String, Object> dataModel) {
        log.debug("Rendering template: {} with data model: {}", templateName, dataModel);

        try {
            Template template = freemarkerConfig.getTemplate(templateName);
            StringWriter writer = new StringWriter();
            template.process(dataModel, writer);
            return writer.toString();
        } catch (IOException e) {
            throw new TemplateException("Failed to load template: " + templateName, e);
        } catch (freemarker.template.TemplateException e) {
            throw new TemplateException("Failed to process template: " + templateName, e);
        }
    }
}
