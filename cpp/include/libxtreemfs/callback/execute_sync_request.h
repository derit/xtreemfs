/*
 * Copyright (c) 2011 by Michael Berlin, Zuse Institute Berlin
 *
 * Licensed under the BSD License, see LICENSE file for details.
 *
 */

#ifndef CPP_INCLUDE_LIBXTREEMFS_CALLBACK_EXECUTE_SYNC_REQUEST_H_
#define CPP_INCLUDE_LIBXTREEMFS_CALLBACK_EXECUTE_SYNC_REQUEST_H_

#include <csignal>
#include <ctime>

#include <algorithm>
#include <boost/cstdint.hpp>
#include <boost/lexical_cast.hpp>
#include <boost/thread/thread.hpp>
#include <boost/thread/tss.hpp>
#include <google/protobuf/descriptor.h>
#include <iostream>
#include <string>

#include "libxtreemfs/options.h"
#include "libxtreemfs/xtreemfs_exception.h"
#include "pbrpc/RPC.pb.h"
#include "rpc/sync_callback.h"
#include "util/error_log.h"
#include "util/logging.h"

namespace xtreemfs {

namespace rpc {
class ClientRequestCallbackInterface;
}  // namespace rpc

/** Is not NULL, if current thread shall get interrupted. */
extern boost::thread_specific_ptr<int> intr_pointer;

/** Sets intr_pointer to interrupt execution of the sync request. */
void InterruptSyncRequest(int signal);

/** Retries to execute the synchronous request "sync_function" up to "options.
 *  max_tries" times and may get interrupted.
 *
 *  The interrupt handler is only registered, if a signal "options.
 *  interrupt_signal" is set. */
template<class ReturnMessageType, class F>
    ReturnMessageType ExecuteSyncRequest(F sync_function,
                                         int max_tries,
                                         const Options& options,
                                         bool delay_last_attempt) {
  // Ignore the signal if no previous signal was found.
  sighandler_t previous_signal_handler = SIG_IGN;
  if (options.interrupt_signal) {
    // Clear current interruption state.
    intr_pointer.reset(NULL);
    // Register signal handler to allow an interruption.
    previous_signal_handler = signal(options.interrupt_signal,
                                     InterruptSyncRequest);
  }

  int attempt = 0;
  bool interrupted = false;
  ReturnMessageType response = NULL;
  // Retry unless maximum tries reached or interrupted.
  while ((++attempt <= max_tries || max_tries == 0) && !interrupted) {
    // Delete any previous response;
    if (response != NULL) {
      response->DeleteBuffers();
      delete response;
    }
    boost::int64_t request_sent = time(NULL);

    response = sync_function();

    if (response->HasFailed()) {
      // Only retry in case of IO errors and further retries left.
      if (response->error()->error_type() == xtreemfs::pbrpc::IO_ERROR
          && (attempt < max_tries || max_tries == 0
              || (attempt == max_tries && delay_last_attempt))) {
        // Log only the first retry.
        if (attempt == 1 && max_tries != 1) {
          std::string retries_left = max_tries == 0 ? "infinite"
              : boost::lexical_cast<std::string>(max_tries-attempt);
          xtreemfs::util::Logging::log->getLog(xtreemfs::util::LEVEL_ERROR)
              << "got no response from server, retrying ("
              << retries_left << " attempts left, waiting at least "
              << options.retry_delay_s << " seconds between two attempts)"
              << std::endl;
        }
        // If the request did return before the timeout was reached, wait until
        // the timeout is up to avoid flooding.
        boost::int64_t delay_time_left;
        do {
          if (options.interrupt_signal && intr_pointer.get() != NULL) {
            // Stop retrying if interrupted between two attempts.
            interrupted = true;
            break;
          }
          delay_time_left = std::max(
              boost::int64_t(0),
              options.retry_delay_s - (time(NULL) - request_sent));
          if (delay_time_left > 0) {
            boost::this_thread::sleep(
                boost::posix_time::millisec(100));
          }
        } while (delay_time_left > 0);
      } else {
        break;  // Do not retry if error occured - throw exception.
      }
    } else {
      // No error happened, check for possible interruption.
    }

    // Have we been interrupted?
    if (options.interrupt_signal && intr_pointer.get() != NULL) {
      if (xtreemfs::util::Logging::log->loggingActive(
              xtreemfs::util::LEVEL_DEBUG)) {
        xtreemfs::util::Logging::log->getLog(xtreemfs::util::LEVEL_DEBUG)
            << "caught interrupt, aborting sync request" << std::endl;
      }
      intr_pointer.reset(NULL);
      // Clear the current response.
      if (response != NULL) {
        response->DeleteBuffers();
      }
      // Free response.
      delete response;
      response = NULL;
      break;  // Do not retry if interrupted.
    }
    if (response != NULL && !response->HasFailed()) {
      break;  // Do not retry if request was successful.
    }
  }
  // Remove signal handler.
  if (options.interrupt_signal) {
    signal(options.interrupt_signal, previous_signal_handler);
  }

  // Max attempts reached or non-IO error seen. Throw an exception.
  if (response != NULL) {
    if (!response->HasFailed()) {
      // Remove signal handler.
      return response;
    }

    // Copy error information in order to delete buffers before the throw.
    xtreemfs::pbrpc::RPCHeader::ErrorResponse* error_resp = response->error();
    const xtreemfs::pbrpc::ErrorType error_type = error_resp->error_type();
    const std::string error_message = error_resp->error_message();
    const int posix_errno = error_resp->posix_errno();
    std::string redirect_to_server_uuid = "";
    if (error_resp->has_redirect_to_server_uuid()) {
        redirect_to_server_uuid = error_resp->redirect_to_server_uuid();
    }
    xtreemfs::util::LogLevel level = xtreemfs::util::LEVEL_ERROR;
    // Complete error text.
    std::string error;

    // Free buffers.
    response->DeleteBuffers();
    delete response;

    // Throw an exception.
    switch (error_type) {
      case xtreemfs::pbrpc::ERRNO:  {
        level = xtreemfs::util::LEVEL_INFO;
        if (posix_errno == xtreemfs::pbrpc::POSIX_ERROR_ENOENT) {
          level = xtreemfs::util::LEVEL_DEBUG;
        }

        std::string posix_errono_string
            = boost::lexical_cast<std::string>(posix_errno);
        const ::google::protobuf::EnumValueDescriptor* enum_desc =
            xtreemfs::pbrpc::POSIXErrno_descriptor()->
                FindValueByNumber(posix_errno);
        if (enum_desc) {
            posix_errono_string = enum_desc->name();
        }
        error = "The server denied the requested operation. Error "
            "Value: " + posix_errono_string + " Error message: " +
            error_message;

        if (xtreemfs::util::Logging::log->loggingActive(level)) {
          xtreemfs::util::Logging::log->getLog(level) << error << std::endl;
        }
        xtreemfs::util::ErrorLog::error_log->AppendError(error);
        throw PosixErrorException(posix_errno, error);
      }
      case xtreemfs::pbrpc::IO_ERROR:  {
        error = "The client encountered a communication error: "
            + error_message;
        if (xtreemfs::util::Logging::log->loggingActive(level)) {
          xtreemfs::util::Logging::log->getLog(level) << error << std::endl;
        }
        xtreemfs::util::ErrorLog::error_log->AppendError(error);
        throw IOException(error_message);
      }
      case xtreemfs::pbrpc::INTERNAL_SERVER_ERROR:  {
        error = "The server returned an internal server error: "
            + error_message;
        if (xtreemfs::util::Logging::log->loggingActive(level)) {
          xtreemfs::util::Logging::log->getLog(level) << error << std::endl;
        }
        xtreemfs::util::ErrorLog::error_log->AppendError(error);
        throw InternalServerErrorException(error_message);
      }
      case xtreemfs::pbrpc::REDIRECT:  {
        level = xtreemfs::util::LEVEL_INFO;
        error = "The server redirected to the current master with "
            "UUID: " + redirect_to_server_uuid;
        if (xtreemfs::util::Logging::log->loggingActive(level)) {
          xtreemfs::util::Logging::log->getLog(level) << error << std::endl;
        }
        xtreemfs::util::ErrorLog::error_log->AppendError(error);
        throw ReplicationRedirectionException(redirect_to_server_uuid);
      }
      default:  {
        std::string error_type_name
            = boost::lexical_cast<std::string>(error_type);
        const ::google::protobuf::EnumValueDescriptor* enum_desc =
            xtreemfs::pbrpc::ErrorType_descriptor()->
                FindValueByNumber(error_type);
        if (enum_desc) {
          error_type_name = enum_desc->name();
        }
        error = "The server returned an error: " + error_type_name
            + " Error Message: " + error_message;
        if (xtreemfs::util::Logging::log->loggingActive(level)) {
          xtreemfs::util::Logging::log->getLog(level) << error << std::endl;
        }
        xtreemfs::util::ErrorLog::error_log->AppendError(error);
        throw XtreemFSException(error);
      }
    }
  } else {
    // No Response given, probably interrupted.
    throw PosixErrorException(EINTR, "The operation was aborted by the user.");
  }
}

/** Executes the request without delaying the last try. */
template<class ReturnMessageType, class F>
    ReturnMessageType ExecuteSyncRequest(F sync_function,
                                         int max_tries,
                                         const Options& options) {
  return ExecuteSyncRequest<ReturnMessageType>(sync_function,
                                               max_tries,
                                               options,
                                               false);
}

}  // namespace xtreemfs

#endif  // CPP_INCLUDE_LIBXTREEMFS_CALLBACK_EXECUTE_SYNC_REQUEST_H_