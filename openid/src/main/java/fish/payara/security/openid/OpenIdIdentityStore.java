/*
 * Copyright (c) 2020 Payara Foundation and/or its affiliates. All rights reserved.
 *
 *  The contents of this file are subject to the terms of either the GNU
 *  General Public License Version 2 only ("GPL") or the Common Development
 *  and Distribution License("CDDL") (collectively, the "License").  You
 *  may not use this file except in compliance with the License.  You can
 *  obtain a copy of the License at
 *  https://github.com/payara/Payara/blob/master/LICENSE.txt
 *  See the License for the specific
 *  language governing permissions and limitations under the License.
 *
 *  When distributing the software, include this License Header Notice in each
 *  file and include the License file at glassfish/legal/LICENSE.txt.
 *
 *  GPL Classpath Exception:
 *  The Payara Foundation designates this particular file as subject to the "Classpath"
 *  exception as provided by the Payara Foundation in the GPL Version 2 section of the License
 *  file that accompanied this code.
 *
 *  Modifications:
 *  If applicable, add the following below the License Header, with the fields
 *  enclosed by brackets [] replaced by your own identifying information:
 *  "Portions Copyright [year] [name of copyright owner]"
 *
 *  Contributor(s):
 *  If you wish your version of this file to be governed by only the CDDL or
 *  only the GPL Version 2, indicate your decision by adding "[Contributor]
 *  elects to include this software in this distribution under the [CDDL or GPL
 *  Version 2] license."  If you don't indicate a single choice of license, a
 *  recipient has the option to distribute your version of this file under
 *  either the CDDL, the GPL Version 2 or to extend the choice of license to
 *  its licensees as provided above.  However, if you add GPL Version 2 code
 *  and therefore, elected the GPL Version 2 license, then the option applies
 *  only if the new code is made subject to such option by the copyright
 *  holder.
 */
package fish.payara.security.openid;

import com.nimbusds.jose.Algorithm;
import fish.payara.security.openid.api.OpenIdConstant;
import fish.payara.security.openid.controller.TokenController;
import fish.payara.security.openid.controller.UserInfoController;
import fish.payara.security.openid.domain.AccessTokenImpl;
import fish.payara.security.openid.domain.IdentityTokenImpl;
import fish.payara.security.openid.domain.OpenIdConfiguration;
import fish.payara.security.openid.domain.OpenIdContextImpl;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import java.util.Set;
import static java.util.stream.Collectors.toSet;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Alternative;
import javax.inject.Inject;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonValue;
import javax.security.enterprise.authentication.mechanism.http.HttpMessageContext;
import javax.security.enterprise.identitystore.CredentialValidationResult;
import javax.security.enterprise.identitystore.IdentityStore;

/**
 * Identity store validates the identity token & access toekn and returns the
 * validation result with the caller name and groups.
 *
 * @author Gaurav Gupta
 */
@Alternative
@ApplicationScoped
public class OpenIdIdentityStore implements IdentityStore {

    @Inject
    private OpenIdContextImpl context;

    @Inject
    private TokenController tokenController;

    @Inject
    private UserInfoController userInfoController;

    @SuppressWarnings("unused") // IdentityStore calls overloads
    public CredentialValidationResult validate(OpenIdCredential credential) {
        HttpMessageContext httpContext = credential.getHttpContext();
        OpenIdConfiguration configuration = credential.getConfiguration();
        IdentityTokenImpl idToken = credential.getIdentityTokenImpl();
        
        Algorithm idTokenAlgorithm = idToken.getTokenJWT().getHeader().getAlgorithm();
        
        Map<String, Object> idTokenClaims;
        if (isNull(context.getIdentityToken())) {
            idTokenClaims = tokenController.validateIdToken(idToken, httpContext, configuration);
        } else {
            // If an ID Token is returned as a result of a token refresh request
            idTokenClaims = tokenController.validateRefreshedIdToken(context.getIdentityToken(), idToken, httpContext, configuration);
        }
        if (idToken.isEncrypted()) {
            idToken.setClaims(idTokenClaims);
        }
        context.setIdentityToken(idToken);

        AccessTokenImpl accessToken = (AccessTokenImpl) credential.getAccessToken();
        if (nonNull(accessToken)) {
            tokenController.validateAccessToken(
                    accessToken, idTokenAlgorithm, context.getIdentityToken().getClaims(), configuration
            );
            context.setAccessToken(accessToken);
        }

        context.setCallerName(getCallerName(configuration));
        context.setCallerGroups(getCallerGroups(configuration));

        return new CredentialValidationResult(
                context.getCallerName(),
                context.getCallerGroups()
        );
    }

    private String getCallerName(OpenIdConfiguration configuration) {
        String callerNameClaim = configuration.getClaimsConfiguration().getCallerNameClaim();
        if (OpenIdConstant.SUBJECT_IDENTIFIER.equals(callerNameClaim)) {
            return context.getSubject();
        }
        String callerName = (String) context.getIdentityToken().getClaim(callerNameClaim);
        if (callerName == null) {
            callerName = (String) context.getAccessToken().getClaim(callerNameClaim);
        }
        if (callerName == null) {
            callerName = context.getClaimsJson().getString(callerNameClaim, null);
        }
        if (callerName == null) {
            callerName = context.getSubject();
        }
        return callerName;
    }

    private Set<String> getCallerGroups(OpenIdConfiguration configuration) {
        String callerGroupsClaim = configuration.getClaimsConfiguration().getCallerGroupsClaim();
        JsonArray groupsUserinfoClaim
                = context.getClaimsJson().getJsonArray(callerGroupsClaim);
        @SuppressWarnings("unchecked")
        List<Object> groupsIdentityClaim
                = (List<Object>) context.getIdentityToken().getClaim(callerGroupsClaim);
        @SuppressWarnings("unchecked")
        List<String> groupsAccessClaim
                = context.getAccessToken().getJwtClaims().getArrayStringClaim(callerGroupsClaim);

        if (nonNull(groupsAccessClaim)) {
            return new HashSet<>(groupsAccessClaim);
        } else if (nonNull(groupsIdentityClaim)) {
            return groupsIdentityClaim.stream()
                    .map(Object::toString)
                    .collect(toSet());
        } else if (nonNull(groupsUserinfoClaim)) {
            return groupsUserinfoClaim.stream()
                    .filter(value -> value.getValueType() == JsonValue.ValueType.STRING)
                    .map(JsonValue::toString)
                    .collect(toSet());
        }
        return Collections.emptySet();
    }

}
