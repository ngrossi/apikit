/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.module.apikit.transform;

import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.api.metadata.DataType;
import org.mule.runtime.api.metadata.MediaType;
import org.mule.runtime.core.api.MuleContext;
import org.mule.runtime.core.api.transformer.Transformer;
import org.mule.runtime.core.internal.transformer.simple.ObjectToString;
import org.mule.runtime.module.json.transformers.JsonToObject;
import org.mule.runtime.module.json.transformers.ObjectToJson;
import org.mule.runtime.module.xml.transformer.jaxb.JAXBMarshallerTransformer;
import org.mule.runtime.module.xml.transformer.jaxb.JAXBUnmarshallerTransformer;

import com.google.common.cache.CacheLoader;
import com.sun.xml.bind.api.JAXBRIContext;

import java.util.HashMap;
import java.util.Map;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;

import org.apache.log4j.Logger;
import org.codehaus.jackson.map.ObjectMapper;

public class TransformerCacheLoader extends CacheLoader<DataTypePair, Transformer>
{

    private static final Logger LOGGER = Logger.getLogger(TransformerCacheLoader.class);
    private final MuleContext muleContext;

    public TransformerCacheLoader(MuleContext muleContext)
    {
        this.muleContext = muleContext;
    }

    @Override
    public Transformer load(DataTypePair dataTypePair) throws Exception
    {
        return resolveTransformer(muleContext, dataTypePair.getSourceDataType(),
            dataTypePair.getResultDataType());
    }

    protected Transformer resolveTransformer(MuleContext muleContext,
                                             DataType sourceDataType,
                                             DataType resultDataType) throws MuleException
    {
        if (isJson(sourceDataType))
        {
            JsonToObject jto = new JsonToObject();
            jto.setReturnDataType(resultDataType);
            jto.setMapper(new ObjectMapper());
            muleContext.getRegistry().applyProcessorsAndLifecycle(jto);
            return jto;
        }
        else if (isJson(resultDataType))
        {
            ObjectToJson otj = new ObjectToJson();
            otj.setSourceClass(sourceDataType.getType());
            otj.setReturnDataType(resultDataType);
            otj.setMapper(new ObjectMapper());
            muleContext.getRegistry().applyProcessorsAndLifecycle(otj);
            return otj;
        }
        else if (isXml(sourceDataType))
        {
            try
            {
                JAXBUnmarshallerTransformer jmt = new JAXBUnmarshallerTransformer(
                    JAXBContext.newInstance(resultDataType.getType()), resultDataType);
                muleContext.getRegistry().applyProcessorsAndLifecycle(jmt);
                return jmt;
            }
            catch (JAXBException e)
            {
                LOGGER.error("Unable to create JAXB unmarshaller for " + resultDataType, e);
            }
        }
        else if (isXml(resultDataType))
        {
            if (String.class.equals(sourceDataType.getType()))
            {
                ObjectToString ots = new ObjectToString();
                ots.setReturnDataType(resultDataType);
                muleContext.getRegistry().applyProcessorsAndLifecycle(ots);
                return ots;
            }
            try
            {
                TransientAnnotationReader reader = new TransientAnnotationReader();
                reader.addTransientField(Throwable.class.getDeclaredField("stackTrace"));
                reader.addTransientMethod(Throwable.class.getDeclaredMethod("getStackTrace"));

                Map<String, Object> jaxbConfig = new HashMap<String, Object>();
                jaxbConfig.put(JAXBRIContext.ANNOTATION_READER, reader);

                JAXBContext jaxbContext = JAXBContext.newInstance(new Class[]{sourceDataType.getType()},
                    jaxbConfig);
                JAXBMarshallerTransformer jut = new JAXBMarshallerTransformer(jaxbContext, resultDataType);
                jut.setSourceClass(sourceDataType.getType());
                muleContext.getRegistry().applyProcessorsAndLifecycle(jut);
                return jut;
            }
            catch (JAXBException e)
            {
                LOGGER.error("Unable to create JAXB marshaller for " + resultDataType, e);
            }
            catch (NoSuchMethodException e)
            {
                LOGGER.error("Unable to create JAXB marshaller for " + resultDataType, e);
            }
            catch (NoSuchFieldException e)
            {
                LOGGER.error("Unable to create JAXB marshaller for " + resultDataType, e);
            }

        }
        return muleContext.getRegistry().lookupTransformer(sourceDataType, resultDataType);
    }

    private boolean isXml(DataType dataType)
    {
        return dataType.getMediaType().matches(MediaType.XML)
               || dataType.getMediaType().matches(MediaType.APPLICATION_XML)
               || dataType.getMediaType().getSubType().endsWith("+xml");
    }

    private boolean isJson(DataType dataType)
    {
        return dataType.getMediaType().matches(MediaType.JSON)
               || dataType.getMediaType().matches(MediaType.APPLICATION_JSON)
               || dataType.getMediaType().getSubType().endsWith("+json");
    }

}
