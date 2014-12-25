package com.dianping.plumber.core.interceptors;

import com.dianping.plumber.core.*;
import com.dianping.plumber.exception.PlumberControllerNotFoundException;
import com.dianping.plumber.exception.PlumberRuntimeException;
import com.dianping.plumber.utils.CollectionUtils;
import com.dianping.plumber.utils.ResponseUtils;
import com.dianping.plumber.view.ViewRenderer;
import org.apache.log4j.Logger;
import org.springframework.context.ApplicationContext;

import javax.servlet.http.HttpServletResponse;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Created with IntelliJ IDEA.
 * Author: liangjun.zhong
 * Date: 14-11-10
 * Time: PM10:01
 * To change this template use File | Settings | File Templates.
 */
public class ControllerInterceptor implements Interceptor {

    private Logger logger = Logger.getLogger(ControllerInterceptor.class);

    @Override
    public ResultType intercept(InvocationContext invocation) throws Exception {

        String controllerName = invocation.getControllerName();
        PlumberController controller = getController(invocation);

        Map<String, Object> paramsForController = invocation.getParamsForController();
        ConcurrentHashMap<String, Object> modelForControllerView = invocation.getModelForControllerView();
        ConcurrentHashMap<String, Object> paramsForPagelets = invocation.getParamsForPagelets();

        ResultType resultType = controller.execute(paramsForController, paramsForPagelets, modelForControllerView);
        if ( resultType!=ResultType.SUCCESS )
            return resultType;

        resultType = invocation.invoke();
        if ( resultType!=ResultType.SUCCESS )
            return resultType;

        PlumberControllerDefinition controllerDefinition = PlumberWorkerDefinitionsRepo.getPlumberControllerDefinition(controllerName);
        String viewSource = controllerDefinition.getViewSource();
        ViewRenderer viewRenderer = PlumberWorkerDefinitionsRepo.getViewRenderer();
        String renderResult = viewRenderer.render(controllerName, viewSource, modelForControllerView);

        HttpServletResponse response = invocation.getResponse();
        ResponseUtils.flushBuffer(response, renderResult);

        List<PlumberPipeDefinition> pipeDefinitions = controllerDefinition.getPipeDefinitions();
        if ( !CollectionUtils.isEmpty(pipeDefinitions) ) {

            int pipeNum = pipeDefinitions.size();
            LinkedBlockingQueue<String> pipeRenderResultQueue = invocation.getPipeRenderResultQueue();
            while ( pipeNum>0 ) {
                String pipeRenderResult = pipeRenderResultQueue.take();
                if ( !PlumberGlobals.EMPTY_RENDER_RESULT.equals(pipeRenderResult) ) {
                    ResponseUtils.flushBuffer(response, pipeRenderResult);
                }
                pipeNum = pipeNum-1;
            }

            // only with pipes need to ensure html to be unclosed, and the close tag will be flushed by framework finally
            ResponseUtils.flushBuffer(response, PlumberGlobals.CHUNKED_END);
        }

        return ResultType.SUCCESS;
    }

    private PlumberController getController(InvocationContext invocation) {

        String controllerName = invocation.getControllerName();
        ApplicationContext applicationContext = invocation.getApplicationContext();
        PlumberController controller = (PlumberController) applicationContext.getBean(controllerName);
        if ( controller==null ) {
            throw new PlumberControllerNotFoundException("can not find your plumberController : "+controllerName+" in spring applicationContext");
        }

        PlumberControllerDefinition definition = PlumberWorkerDefinitionsRepo.getPlumberControllerDefinition(controllerName);
        List<Field> paramFromRequestFields = definition.getParamFromRequestFields();
        Map<String, Object> paramsForController = invocation.getParamsForController();
        injectAnnotationFields(controllerName, controller, paramFromRequestFields, paramsForController);

        return controller;
    }

    private void injectAnnotationFields(String controllerName,
                                        PlumberController controller,
                                        List<Field> paramFromRequestFields,
                                        Map<String, Object> paramsForController) {

        if ( !CollectionUtils.isEmpty(paramFromRequestFields) && paramsForController!=null ) {
            for ( Field field : paramFromRequestFields ) {
                String fieldName = field.getName();
                Object fieldValue = paramsForController.get(fieldName);
                if( fieldValue!=null ){
                    try {
                        field.set(controller, fieldValue);
                    } catch (Exception e) {
                        String msg = "inject annotation field of " + fieldName + " for controller "+ controllerName + " failure";
                        logger.error(msg, new PlumberRuntimeException(msg, e));
                    }
                }
            }
        }

    }

}
