/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.module.apikit.validation;

import static org.mule.module.apikit.CharsetUtils.getEncoding;

import com.github.fge.jsonschema.core.report.LogLevel;
import com.github.fge.jsonschema.core.report.ProcessingMessage;
import org.mule.module.apikit.CharsetUtils;
import org.mule.module.apikit.EventHelper;
import org.mule.runtime.api.metadata.DataType;
import org.mule.runtime.core.api.Event;
import org.mule.runtime.core.api.MuleContext;
import org.mule.runtime.core.api.registry.RegistrationException;
import org.mule.module.apikit.exception.BadRequestException;
import org.mule.module.apikit.validation.cache.JsonSchemaCache;
import org.mule.module.apikit.validation.io.JsonUtils;
import org.mule.raml.interfaces.model.IRaml;
import org.mule.runtime.core.util.IOUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.fge.jsonschema.core.exceptions.ProcessingException;
import com.github.fge.jsonschema.core.report.ProcessingReport;
import com.github.fge.jsonschema.main.JsonSchema;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.util.Iterator;
import java.util.concurrent.ExecutionException;

import org.raml.parser.utils.StreamUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.github.fge.jsonschema.core.report.LogLevel.ERROR;
import static com.github.fge.jsonschema.core.report.LogLevel.WARNING;

public class RestJsonSchemaValidator extends AbstractRestSchemaValidator
{
    private static final String JSON_SCHEMA_FAIL_ON_WARNING_KEY = "raml.json_schema.fail_on_warning";

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    public RestJsonSchemaValidator(MuleContext muleContext)
    {
        super(muleContext);
    }

    @Override
    public Event validate(String configId, String schemaPath, Event muleEvent, IRaml api) throws BadRequestException
    {
        Event newMuleEvent = muleEvent;
        try
        {
            JsonNode data;
            Object input = muleEvent.getMessage().getPayload().getValue();
            if (input instanceof InputStream)
            {
                logger.debug("transforming payload to perform JSON Schema validation");
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try
                {
                    IOUtils.copyLarge((InputStream) input, baos);
                }
                finally
                {
                    IOUtils.closeQuietly((InputStream) input);
                }

                String charset = CharsetUtils.getEncoding(muleEvent, muleContext, baos.toByteArray(), logger);
                DataType dataType = muleEvent.getMessage().getPayload().getDataType();

                org.mule.runtime.api.metadata.DataTypeBuilder sourceDataTypeBuilder = DataType.builder();
                sourceDataTypeBuilder.type(muleEvent.getMessage().getPayload().getClass());
                sourceDataTypeBuilder.mediaType(dataType.getMediaType());
                sourceDataTypeBuilder.charset(charset);
                DataType sourceDataType = sourceDataTypeBuilder.build();//DataTypeFactory.create(event.getMessage().getPayload().getClass(), msgMimeType);
                newMuleEvent = EventHelper.setPayload(muleEvent, new ByteArrayInputStream(baos.toByteArray()), sourceDataType.getMediaType());

                //convert to string to remove BOM
                String str = StreamUtils.toString(new ByteArrayInputStream(baos.toByteArray()));
                data = JsonUtils.parseJson(new StringReader(str));
            }
            else if (input instanceof String)
            {
                data = JsonUtils.parseJson(new StringReader((String) input));
            }
            else if (input instanceof byte[])
            {
                String encoding = getEncoding(muleEvent, muleContext, (byte[]) input, logger);
                input = org.raml.v2.internal.utils.StreamUtils.trimBom((byte[]) input);
                data = JsonUtils.parseJson(new InputStreamReader(new ByteArrayInputStream((byte[]) input), encoding));

                //update message encoding

                newMuleEvent = EventHelper.setPayload(muleEvent, input, encoding);
            }
            else
            {
                throw new BadRequestException("Don't know how to parse " + input.getClass().getName());
            }

            JsonSchema schema = JsonSchemaCache.getJsonSchemaCache(muleContext, configId, api).get(schemaPath);
            ProcessingReport report = schema.validate(data);
            Iterator<ProcessingMessage> iterator = report.iterator();

            while (iterator.hasNext())
            {
                ProcessingMessage next = iterator.next();
                LogLevel logLevel = next.getLogLevel();
                String message = next.toString();

                boolean failOnWarning = Boolean.valueOf(
                        System.getProperty(JSON_SCHEMA_FAIL_ON_WARNING_KEY, "false"));

                if (logLevel.equals(ERROR) || (logLevel.equals(WARNING) && failOnWarning))
                {
                    logger.info("Schema validation failed: " + message);
                    throw new BadRequestException(message);
                }
            }
        }
        catch (ExecutionException e)
        {
            throw new BadRequestException(e);
        }
        catch (RegistrationException e)
        {
            throw new BadRequestException(e);
        }
        catch (IOException e)
        {
            throw new BadRequestException(e);
        }
        catch (ProcessingException e)
        {
            throw new BadRequestException(e);
        }
        return newMuleEvent;
    }
}
