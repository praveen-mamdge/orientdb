package com.orientechnologies.orient.core.serialization.serializer.record.json.vserializers;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.serialization.serializer.record.json.ValueSerializer;

import java.io.IOException;

public final class StringSerializer implements ValueSerializer {
  @Override
  public void toJSON(JsonGenerator generator, Object value) throws IOException {
    generator.writeString(value.toString());
  }

  @Override
  public Object fromJSON(JsonParser parser, ODocument owner) throws IOException {
    return parser.getValueAsString();
  }

  @Override
  public String typeId() {
    return SerializerIDs.STRING;
  }

  @Override
  public JsonToken[] startTokens() {
    return new JsonToken[] {JsonToken.VALUE_STRING};
  }
}
