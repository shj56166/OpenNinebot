package com.motexa.openninebot;
/** The media transport and codec do not depend on how frames enter the Surface. */
interface CastVideoInput extends com.motexa.vehiclecast.VideoSource {
    default String sourceId() { return "mirror"; }
    default void encodedFrame() {}
    default void awaitDetached() {}
    default org.json.JSONObject frameState() { return new org.json.JSONObject(); }
}
