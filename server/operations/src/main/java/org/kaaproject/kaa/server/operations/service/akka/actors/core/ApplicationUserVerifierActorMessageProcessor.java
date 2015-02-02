/*
 * Copyright 2014 CyberVision, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kaaproject.kaa.server.operations.service.akka.actors.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.kaaproject.kaa.common.dto.user.UserVerifierDto;
import org.kaaproject.kaa.server.common.thrift.gen.operations.Notification;
import org.kaaproject.kaa.server.common.verifier.UserVerifier;
import org.kaaproject.kaa.server.common.verifier.UserVerifierCallback;
import org.kaaproject.kaa.server.common.verifier.UserVerifierContext;
import org.kaaproject.kaa.server.common.verifier.UserVerifierErrorCode;
import org.kaaproject.kaa.server.common.verifier.UserVerifierLifecycleException;
import org.kaaproject.kaa.server.operations.service.akka.messages.core.user.verification.UserVerificationRequestMessage;
import org.kaaproject.kaa.server.operations.service.akka.messages.core.user.verification.UserVerificationResponseMessage;
import org.kaaproject.kaa.server.operations.service.user.EndpointUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import akka.actor.ActorRef;

public class ApplicationUserVerifierActorMessageProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(ApplicationUserVerifierActorMessageProcessor.class);

    private final EndpointUserService endpointUserService;

    private final String applicationId;

    /** The log appenders */
    private Map<Integer, UserVerifier> userVerifiers;

    ApplicationUserVerifierActorMessageProcessor(EndpointUserService endpointUserService, String applicationId) {
        this.applicationId = applicationId;
        this.endpointUserService = endpointUserService;
        initUserVerifiers();
    }

    private void initUserVerifiers() {
        this.userVerifiers = new HashMap<Integer, UserVerifier>();
        for (UserVerifierDto dto : endpointUserService.findUserVerifiers(applicationId)) {
            try {
                LOG.trace("Initializing user verifier for {}", dto);
                UserVerifier verifier = createUserVerifier(dto);
                userVerifiers.put(dto.getVerifierId(), verifier);
            } catch (Exception e) {
                LOG.error("Failed to create user verifier", e);
            }
        }
    }

    private UserVerifier createUserVerifier(UserVerifierDto verifierDto) throws Exception {
        if (verifierDto == null) {
            throw new IllegalArgumentException("verifier dto can't be null");
        }
        try {
            @SuppressWarnings("unchecked")
            Class<UserVerifier> verifierClass = (Class<UserVerifier>) Class.forName(verifierDto.getClassName());
            UserVerifier userVerifier = verifierClass.newInstance();
            userVerifier.init(new UserVerifierContext(verifierDto));
            userVerifier.start();
            return userVerifier;
        } catch (ClassNotFoundException e) {
            LOG.error("Unable to find custom verifier class {}", verifierDto.getClassName());
            throw e;
        } catch (InstantiationException | IllegalAccessException | UserVerifierLifecycleException e) {
            LOG.error("Unable to instantiate custom verifier from class {}", verifierDto.getClassName());
            throw e;
        }
    }

    public void verifyUser(UserVerificationRequestMessage message) {
        UserVerifier verifier = userVerifiers.get(message.getVerifierId());
        if (verifier != null) {

        } else {
            message.getOriginator().tell(UserVerificationResponseMessage.failure(UserVerifierErrorCode.NO_VERIFIER_CONFIGURED),
                    ActorRef.noSender());
        }
    }

    public void processNotification(Notification notification) {
        LOG.debug("Process user verifier notification [{}]", notification);
        int verifierId = notification.getUserVerifierId();
        switch (notification.getOp()) {
        case ADD_USER_VERIFIER:
            addUserVerifier(verifierId);
            break;
        case REMOVE_USER_VERIFIER:
            removeUserVerifier(verifierId);
            break;
        case UPDATE_USER_VERIFIER:
            removeUserVerifier(verifierId);
            addUserVerifier(verifierId);
            break;
        default:
            LOG.debug("[{}][{}] Operation [{}] is not supported.", applicationId, verifierId, notification.getOp());
        }
    }

    private void addUserVerifier(int verifierId) {
        LOG.info("[{}] Adding user verifier with id [{}].", applicationId, verifierId);
        if (!userVerifiers.containsKey(verifierId)) {
            UserVerifierDto verifierDto = endpointUserService.findUserVerifier(applicationId, verifierId);
            if (verifierDto != null) {
                try {
                    userVerifiers.put(verifierId, createUserVerifier(verifierDto));
                    LOG.info("[{}] user verifier [{}] registered.", applicationId, verifierId);
                } catch (Exception e) {
                    LOG.error("Failed to create user verifier", e);
                }
            }
        } else {
            LOG.info("[{}] User verifier [{}] is already registered.", applicationId, verifierId);
        }
    }

    private void removeUserVerifier(int appenderId) {
        if (userVerifiers.containsKey(appenderId)) {
            LOG.info("[{}] Stopping user verifier with id [{}].", applicationId, appenderId);
            userVerifiers.remove(appenderId).stop();
        } else {
            LOG.warn("[{}] Can't remove unregistered user verifier with id [{}]", applicationId, appenderId);
        }
    }

    void preStart() {
    };

    void postStop() {
        for (Entry<Integer, UserVerifier> verifier : userVerifiers.entrySet()) {
            LOG.info("[{}] Stopping user verifier with id [{}].", applicationId, verifier.getKey());
            verifier.getValue().stop();
        }
    }

    public static class DefaultVerifierCallback implements UserVerifierCallback {
        
        private final ActorRef endpointActor;

        public DefaultVerifierCallback(ActorRef endpointActor) {
            super();
            this.endpointActor = endpointActor;
        }
        
        private void tell(UserVerificationResponseMessage msg){
            endpointActor.tell(msg, ActorRef.noSender());
        }

        @Override
        public void onSuccess() {
            tell(UserVerificationResponseMessage.success());
        }

        @Override
        public void onTokenInvalid() {
            tell(UserVerificationResponseMessage.failure(UserVerifierErrorCode.TOKEN_INVALID));
        }

        @Override
        public void onTokenExpired() {
            tell(UserVerificationResponseMessage.failure(UserVerifierErrorCode.TOKEN_EXPIRED));
        }

        @Override
        public void onVerificationFailure(String reason) {
            tell(UserVerificationResponseMessage.failure(UserVerifierErrorCode.OTHER, reason));
        }

        @Override
        public void onInternalError() {
            tell(UserVerificationResponseMessage.failure(UserVerifierErrorCode.INTERNAL_ERROR));
        }

        @Override
        public void onInternalError(String reason) {
            tell(UserVerificationResponseMessage.failure(UserVerifierErrorCode.TOKEN_EXPIRED, reason));
        }

        @Override
        public void onConnectionError() {
            tell(UserVerificationResponseMessage.failure(UserVerifierErrorCode.CONNECTION_ERROR));
        }

        @Override
        public void onConnectionError(String reason) {
            tell(UserVerificationResponseMessage.failure(UserVerifierErrorCode.CONNECTION_ERROR, reason));
        }

        @Override
        public void onRemoteError() {
            tell(UserVerificationResponseMessage.failure(UserVerifierErrorCode.REMOTE_ERROR));
        }

        @Override
        public void onRemoteError(String reason) {
            tell(UserVerificationResponseMessage.failure(UserVerifierErrorCode.REMOTE_ERROR, reason));
        }
    }
}
