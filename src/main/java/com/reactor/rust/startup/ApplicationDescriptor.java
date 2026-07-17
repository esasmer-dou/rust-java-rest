package com.reactor.rust.startup;

import com.reactor.rust.di.BeanContainer;

import java.util.List;

/** Build-time generated application metadata and component factory contract. */
public interface ApplicationDescriptor {

    List<String> components();

    List<String> routes();

    List<String> properties();

    int registerComponents(BeanContainer container, String basePackage);
}
