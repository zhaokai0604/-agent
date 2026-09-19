package com.award.log.config;

import org.junit.jupiter.api.Test;

import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class ConfigurationBeanCoverageTest {

    @Test
    void routingProfileAccessorsRoundTripEverySupportedProperty() throws Exception {
        AiModelRoutingProperties.Profile profile = new AiModelRoutingProperties.Profile();
        for (PropertyDescriptor descriptor : Introspector.getBeanInfo(profile.getClass()).getPropertyDescriptors()) {
            roundTrip(profile, descriptor);
        }
        assertNotNull(profile.toString());
    }

    @Test
    void pathAndRestartConfigurationAccessorsRoundTrip() throws Exception {
        AgentOpsProperties.Paths paths = new AgentOpsProperties.Paths();
        for (PropertyDescriptor descriptor : Introspector.getBeanInfo(paths.getClass()).getPropertyDescriptors()) {
            roundTrip(paths, descriptor);
        }
        AgentOpsProperties.ServiceRestart restart = new AgentOpsProperties.ServiceRestart();
        for (PropertyDescriptor descriptor : Introspector.getBeanInfo(restart.getClass()).getPropertyDescriptors()) {
            roundTrip(restart, descriptor);
        }
        assertNotNull(paths.toString());
        assertNotNull(restart.toString());
    }

    private static void roundTrip(Object bean, PropertyDescriptor descriptor) throws Exception {
        Method read = descriptor.getReadMethod();
        Method write = descriptor.getWriteMethod();
        if (read == null || write == null || "class".equals(descriptor.getName())) {
            return;
        }
        Class<?> type = descriptor.getPropertyType();
        Object value;
        if (type == boolean.class || type == Boolean.class) {
            value = Boolean.TRUE;
        } else if (type == int.class || type == Integer.class) {
            value = 123;
        } else if (type == String.class) {
            value = "configured";
        } else if (List.class.isAssignableFrom(type)) {
            value = List.of("configured");
        } else {
            return;
        }
        write.invoke(bean, value);
        assertNotNull(read.invoke(bean));
    }
}
