package org.pipelineframework.csv.pilot;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import io.smallrye.config.ConfigSourceContext;
import io.smallrye.config.ConfigValue;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ssm.model.*;
import static org.assertj.core.api.Assertions.*;

class PilotSigningConfigSourceFactoryTest {
    private static final String ARN = "arn:aws:ssm:us-east-2:123456789012:parameter/tpf/durable/pilot/resume-token/g-" + "a".repeat(32);
    private static final String SECRET = "pipeline.orchestrator.resume-token-secret";
    private static final String KEY = "exact key bytes retained without truncation " + "x".repeat(40);
    private Map<String,String> valid() { return new HashMap<>(Map.of("csv.pilot.resume-token-parameter-arn", ARN,
        "csv.pilot.resume-token-parameter-version", "1", "csv.pilot.signing-lifecycle-validation", "validated")); }
    private ConfigSourceContext context(Map<String,String> values) { return new ConfigSourceContext() {
        @Override public ConfigValue getValue(String name) { return ConfigValue.builder().withName(name).withValue(values.get(name)).build(); }
        @Override public Iterator<String> iterateNames() { return values.keySet().iterator(); }
    }; }
    private GetParameterResponse response(String arn,String name,long version,ParameterType type,String value) {
        return GetParameterResponse.builder().parameter(Parameter.builder().arn(arn).name(name).version(version).type(type).value(value).build()).build();
    }
    private GetParameterResponse validResponse() { return response(ARN,ARN.substring(ARN.indexOf(":parameter/")+10),1,ParameterType.SECURE_STRING,KEY); }
    @Test void exactVersionDecryptsAndPreservesEveryKeyByte() {
        var factory=new PilotSigningConfigSourceFactory(request->{assertThat(request.name()).isEqualTo(ARN+":1");assertThat(request.withDecryption()).isTrue();return validResponse();});
        var source=factory.getConfigSources(context(valid())).iterator().next();
        assertThat(source.getValue(SECRET)).isEqualTo(KEY);assertThat(source.getOrdinal()).isEqualTo(500);
        assertThat(source.getPropertyNames()).containsExactly(SECRET);
    }
    @Test void noSelectedSourceDoesNotReadOrManufactureAKeyAndPreservesLocalProofPath() {
        var calls=new AtomicInteger();var factory=new PilotSigningConfigSourceFactory(request->{calls.incrementAndGet();return validResponse();});
        assertThat(factory.getConfigSources(context(Map.of()))).isEmpty();
        assertThat(factory.getConfigSources(context(Map.of(SECRET,KEY)))).isEmpty();assertThat(calls).hasValue(0);
    }
    @Test void incompleteBlankUnvalidatedOrFloatingReferencesFailBeforeRead() {
        var calls=new AtomicInteger();var factory=new PilotSigningConfigSourceFactory(request->{calls.incrementAndGet();return validResponse();});
        for(String name:valid().keySet()) {
            var omitted=valid();omitted.remove(name);assertThatThrownBy(()->factory.getConfigSources(context(omitted))).isInstanceOf(IllegalArgumentException.class);
            var blank=valid();blank.put(name,"");assertThatThrownBy(()->factory.getConfigSources(context(blank))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->factory.getConfigSources(context(Map.of(name,"")))).isInstanceOf(IllegalArgumentException.class);
        }
        for(String version:new String[]{"2","latest","0","01"," 1","1:label"}) {var values=valid();values.put("csv.pilot.resume-token-parameter-version",version);assertThatThrownBy(()->factory.getConfigSources(context(values))).isInstanceOf(IllegalArgumentException.class);}
        for(String arn:new String[]{ARN+":1",ARN.replace("resume-token","worker"),"/tpf/durable/pilot/resume-token/g-"+"a".repeat(32),ARN.replace("g-","mutable-")}) {var values=valid();values.put("csv.pilot.resume-token-parameter-arn",arn);assertThatThrownBy(()->factory.getConfigSources(context(values))).isInstanceOf(IllegalArgumentException.class);}
        assertThat(calls).hasValue(0);
    }
    @Test void plaintextPresenceIncludingBlankIsRejectedWithSsm() {
        for(String name:new String[]{SECRET,"PIPELINE_ORCHESTRATOR_RESUME_TOKEN_SECRET"}) for(String secret:new String[]{KEY,""}) {
            var values=valid();values.put(name,secret);
            assertThatThrownBy(()->new PilotSigningConfigSourceFactory(request->{throw new AssertionError("Must not read");}).getConfigSources(context(values))).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void physicalIdentityNameVersionAndTypeAreVerified() {
        String name=ARN.substring(ARN.indexOf(":parameter/")+10);
        for(var response:new GetParameterResponse[]{response(ARN+"other",name,1,ParameterType.SECURE_STRING,KEY),response(ARN,name+"other",1,ParameterType.SECURE_STRING,KEY),response(ARN,name,2,ParameterType.SECURE_STRING,KEY),response(ARN,name,1,ParameterType.STRING,KEY),GetParameterResponse.builder().build()}) {
            assertThatThrownBy(()->new PilotSigningConfigSourceFactory(request->response).getConfigSources(context(valid()))).isInstanceOf(IllegalStateException.class).hasMessageNotContaining(KEY);
        }
    }
    @Test void keyBoundsRejectMissingShortBlankAndOversizeUtf8WithoutTruncation() {
        String name=ARN.substring(ARN.indexOf(":parameter/")+10);
        for(String key:new String[]{""," ".repeat(32),"x".repeat(31),"é".repeat(2049)}) {
            assertThatThrownBy(()->new PilotSigningConfigSourceFactory(request->response(ARN,name,1,ParameterType.SECURE_STRING,key)).getConfigSources(context(valid()))).isInstanceOf(IllegalStateException.class).hasMessageNotContaining(key.length()>0?key:"sensitive provider payload");
        }
    }
    @Test void providerFailureNeverExposesItsCauseOrSecret() {
        assertThatThrownBy(()->new PilotSigningConfigSourceFactory(request->{throw new RuntimeException(KEY);}).getConfigSources(context(valid())))
            .isInstanceOf(IllegalStateException.class).hasNoCause().hasMessageNotContaining(KEY);
    }
}
