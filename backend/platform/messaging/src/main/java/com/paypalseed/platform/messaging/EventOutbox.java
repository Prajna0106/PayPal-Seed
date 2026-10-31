package com.paypalseed.platform.messaging;

public interface EventOutbox { void publish(Object event); }
