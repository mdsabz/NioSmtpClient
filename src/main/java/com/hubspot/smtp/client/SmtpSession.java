package com.hubspot.smtp.client;

import static io.netty.handler.codec.smtp.LastSmtpContent.EMPTY_LAST_CONTENT;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CharMatcher;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.base.Throwables;
import com.google.common.collect.Sets;
import com.hubspot.smtp.messages.MessageContent;
import com.hubspot.smtp.utils.SmtpResponses;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.smtp.DefaultSmtpRequest;
import io.netty.handler.codec.smtp.SmtpCommand;
import io.netty.handler.codec.smtp.SmtpRequest;
import io.netty.handler.codec.smtp.SmtpResponse;
import io.netty.handler.ssl.SslHandler;

public class SmtpSession {
  // https://tools.ietf.org/html/rfc2920#section-3.1
  // In particular, the commands RSET, MAIL FROM, SEND FROM, SOML FROM, SAML FROM,
  // and RCPT TO can all appear anywhere in a pipelined command group.
  private static final Set<SmtpCommand> VALID_ANYWHERE_PIPELINED_COMMANDS = Sets.newHashSet(
      SmtpCommand.RSET, SmtpCommand.MAIL, SmtpCommand.RCPT);

  // https://tools.ietf.org/html/rfc2920#section-3.1
  // The EHLO, DATA, VRFY, EXPN, TURN, QUIT, and NOOP commands can only appear
  // as the last command in a group since their success or failure produces
  // a change of state which the client SMTP must accommodate.
  private static final Set<SmtpCommand> VALID_AT_END_PIPELINED_COMMANDS = Sets.newHashSet(
      SmtpCommand.RSET,
      SmtpCommand.MAIL,
      SmtpCommand.RCPT,
      SmtpCommand.EHLO,
      SmtpCommand.DATA,
      SmtpCommand.VRFY,
      SmtpCommand.EXPN,
      SmtpCommand.QUIT,
      SmtpCommand.NOOP);

  private static final Joiner COMMA_JOINER = Joiner.on(", ");
  private static final Splitter WHITESPACE_SPLITTER = Splitter.on(CharMatcher.WHITESPACE);
  private static final SmtpCommand STARTTLS_COMMAND = SmtpCommand.valueOf("STARTTLS");
  private static final SmtpCommand AUTH_COMMAND = SmtpCommand.valueOf("AUTH");
  private static final String AUTH_PLAIN_MECHANISM = "PLAIN";
  private static final String AUTH_LOGIN_MECHANISM = "LOGIN";
  private static final String CRLF = "\r\n";

  private final Channel channel;
  private final ResponseHandler responseHandler;
  private final ExecutorService executorService;
  private final SmtpSessionConfig config;
  private final CompletableFuture<Void> closeFuture;

  private volatile EnumSet<SupportedExtensions> supportedExtensions = EnumSet.noneOf(SupportedExtensions.class);
  private volatile boolean isAuthPlainSupported;
  private volatile boolean isAuthLoginSupported;

  SmtpSession(Channel channel, ResponseHandler responseHandler, ExecutorService executorService, SmtpSessionConfig config) {
    this.channel = channel;
    this.responseHandler = responseHandler;
    this.executorService = executorService;
    this.config = config;
    this.closeFuture = new CompletableFuture<>();

    this.channel.pipeline().addLast(new ErrorHandler());
  }

  public CompletableFuture<Void> getCloseFuture() {
    return closeFuture;
  }

  public CompletableFuture<Void> close() {
    this.channel.close();
    return closeFuture;
  }

  public CompletableFuture<SmtpClientResponse> startTls() {
    Preconditions.checkState(!isEncrypted(), "This connection is already using TLS");

    return send(new DefaultSmtpRequest(STARTTLS_COMMAND)).thenCompose(r -> {
      if (SmtpResponses.isError(r)) {
        return CompletableFuture.completedFuture(r);
      } else {
        return performTlsHandshake(r);
      }
    });
  }

  private CompletionStage<SmtpClientResponse> performTlsHandshake(SmtpClientResponse r) {
    CompletableFuture<SmtpClientResponse> ourFuture = new CompletableFuture<>();

    SslHandler sslHandler = new SslHandler(config.getSSLEngineSupplier().get());
    channel.pipeline().addFirst(sslHandler);

    sslHandler.handshakeFuture().addListener(nettyFuture -> {
      if (nettyFuture.isSuccess()) {
        ourFuture.complete(r);
      } else {
        ourFuture.completeExceptionally(nettyFuture.cause());
        close();
      }
    });

    return ourFuture;
  }

  public boolean isEncrypted() {
    return channel.pipeline().get(SslHandler.class) != null;
  }

  public CompletableFuture<SmtpClientResponse> send(SmtpRequest request) {
    Preconditions.checkNotNull(request);

    CompletableFuture<SmtpResponse[]> responseFuture = responseHandler.createResponseFuture(1, () -> createDebugString(request));
    responseFuture = interceptResponse(request, responseFuture);
    channel.writeAndFlush(request);

    return applyOnExecutor(responseFuture, r -> new SmtpClientResponse(r[0], this));
  }

  public CompletableFuture<SmtpClientResponse> send(MessageContent content) {
    Preconditions.checkNotNull(content);

    CompletableFuture<SmtpResponse[]> responseFuture = responseHandler.createResponseFuture(1, () -> "message contents");

    writeContent(content);
    channel.flush();

    return applyOnExecutor(responseFuture, r -> new SmtpClientResponse(r[0], this));
  }

  public CompletableFuture<SmtpClientResponse[]> sendPipelined(SmtpRequest... requests) {
    Preconditions.checkNotNull(requests);

    return sendPipelined(null, requests);
  }

  public CompletableFuture<SmtpClientResponse[]> sendPipelined(MessageContent content, SmtpRequest... requests) {
    Preconditions.checkState(isSupported(SupportedExtensions.PIPELINING), "Pipelining is not supported on this server");
    Preconditions.checkNotNull(requests);
    checkValidPipelinedRequest(requests);

    int expectedResponses = requests.length + (content == null ? 0 : 1);
    CompletableFuture<SmtpResponse[]> responseFuture = responseHandler.createResponseFuture(expectedResponses, () -> createDebugString(requests));

    if (content != null) {
      writeContent(content);
    }
    for (SmtpRequest r : requests) {
      channel.write(r);
    }

    channel.flush();

    return applyOnExecutor(responseFuture, rs -> {
      SmtpClientResponse[] smtpClientResponses = new SmtpClientResponse[rs.length];
      for (int i = 0; i < smtpClientResponses.length; i++) {
        smtpClientResponses[i] = new SmtpClientResponse(rs[i], this);
      }
      return smtpClientResponses;
    });
  }

  public CompletableFuture<SmtpClientResponse> authPlain(String username, String password) {
    Preconditions.checkState(isAuthPlainSupported, "Auth plain is not supported on this server");

    String s = String.format("%s\0%s\0%s", username, username, password);
    return send(new DefaultSmtpRequest(AUTH_COMMAND, AUTH_PLAIN_MECHANISM, encodeBase64(s)));
  }

  public CompletableFuture<SmtpClientResponse> authLogin(String username, String password) {
    Preconditions.checkState(isAuthLoginSupported, "Auth login is not supported on this server");

    return send(new DefaultSmtpRequest(AUTH_COMMAND, AUTH_LOGIN_MECHANISM, encodeBase64(username))).thenCompose(r -> {
      if (SmtpResponses.isError(r)) {
        return CompletableFuture.completedFuture(r);
      } else {
        return sendAuthLoginPassword(password);
      }
    });
  }

  private CompletionStage<SmtpClientResponse> sendAuthLoginPassword(String password) {
    CompletableFuture<SmtpResponse[]> responseFuture = responseHandler.createResponseFuture(1, () -> "auth login password");

    String passwordResponse = encodeBase64(password) + CRLF;
    ByteBuf passwordBuffer = channel.alloc().buffer().writeBytes(passwordResponse.getBytes(StandardCharsets.UTF_8));
    channel.writeAndFlush(passwordBuffer);

    return applyOnExecutor(responseFuture, loginResponse -> new SmtpClientResponse(loginResponse[0], this));
  }

  private String encodeBase64(String s) {
    return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
  }

  public boolean isAuthPlainSupported() {
    return isAuthPlainSupported;
  }

  public boolean isAuthLoginSupported() {
    return isAuthLoginSupported;
  }

  private void writeContent(MessageContent content) {
    if (isSupported(SupportedExtensions.EIGHT_BIT_MIME)) {
      channel.write(content.get8BitMimeEncodedContent());
    } else {
      channel.write(content.get7BitEncodedContent());
    }

    // SmtpRequestEncoder requires that we send an SmtpContent instance after the DATA command
    // to unset its contentExpected state.
    channel.write(EMPTY_LAST_CONTENT);
  }

  private CompletableFuture<SmtpResponse[]> interceptResponse(SmtpRequest request, CompletableFuture<SmtpResponse[]> originalFuture) {
    if (!request.command().equals(SmtpCommand.EHLO)) {
      return originalFuture;
    }

    return originalFuture.whenComplete((response, ignored) -> {
      if (response != null && response.length > 0) {
        setSupportedExtensions(response[0].details());
      }
    });
  }

  @VisibleForTesting
  void setSupportedExtensions(List<CharSequence> details) {
    isAuthLoginSupported = false;
    isAuthPlainSupported = false;

    EnumSet<SupportedExtensions> discoveredExtensions = EnumSet.noneOf(SupportedExtensions.class);

    for (CharSequence ext : details) {
      List<String> parts = WHITESPACE_SPLITTER.splitToList(ext);
      SupportedExtensions.find(parts.get(0)).ifPresent(discoveredExtensions::add);

      if (parts.get(0).equalsIgnoreCase("auth") && parts.size() > 1) {
        for (int i = 1; i < parts.size(); i++) {
          if (parts.get(i).equalsIgnoreCase("plain")) {
            isAuthPlainSupported = true;
          } else if (parts.get(i).equalsIgnoreCase("login")) {
            isAuthLoginSupported = true;
          }
        }
      }
    }

    this.supportedExtensions = discoveredExtensions;
  }

  @VisibleForTesting
  static String createDebugString(SmtpRequest... requests) {
    return COMMA_JOINER.join(Arrays.stream(requests)
        .map(r -> r.command().equals(AUTH_COMMAND) ? "<redacted-auth-command>" : requestToString(r))
        .collect(Collectors.toList()));
  }

  private static  String requestToString(SmtpRequest request) {
    return String.format("%s %s", request.command().name(), Joiner.on(" ").join(request.parameters()));
  }

  private static void checkValidPipelinedRequest(SmtpRequest[] requests) {
    Preconditions.checkArgument(requests.length > 0, "You must provide requests to pipeline");

    for (int i = 0; i < requests.length; i++) {
      SmtpCommand command = requests[i].command();
      boolean isLastRequest = (i == requests.length - 1);

      if (isLastRequest) {
        Preconditions.checkArgument(VALID_AT_END_PIPELINED_COMMANDS.contains(command),
            command.name() + " cannot be used in a pipelined request");
      } else {
        String errorMessage = VALID_AT_END_PIPELINED_COMMANDS.contains(command) ?
            " must appear last in a pipelined request" : " cannot be used in a pipelined request";

        Preconditions.checkArgument(VALID_ANYWHERE_PIPELINED_COMMANDS.contains(command),
            command.name() + errorMessage);
      }
    }
  }

  private <R, T> CompletableFuture<R> applyOnExecutor(CompletableFuture<T> eventLoopFuture, Function<T, R> mapper) {
    // use handleAsync to ensure exceptions and other callbacks are completed on the ExecutorService thread
    return eventLoopFuture.handleAsync((rs, e) -> {
      if (e != null) {
        throw Throwables.propagate(e);
      }

      return mapper.apply(rs);
    }, executorService);
  }

  public boolean isSupported(SupportedExtensions ext) {
    return supportedExtensions.contains(ext);
  }

  private class ErrorHandler extends ChannelInboundHandlerAdapter {
    private Throwable cause;

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
      this.cause = cause;
      ctx.close();
    }

    @Override
    @SuppressFBWarnings("NP_NONNULL_PARAM_VIOLATION") // https://github.com/findbugsproject/findbugs/issues/79
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
      if (cause != null) {
        closeFuture.completeExceptionally(cause);
      } else {
        closeFuture.complete(null);
      }

      super.channelInactive(ctx);
    }
  }
}
