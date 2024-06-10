#include "mailuser.h"
#include "netbuffer.h"
#include "server.h"
#include "util.h"

#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/utsname.h>
#include <unistd.h>

#define MAX_LINE_LENGTH 1024

typedef enum state {
    Undefined,
    // TODO: Add additional states as necessary
    Greeted,
    Receiver_Ready,
    Send_Ready,
} State;

typedef struct smtp_state {
    int fd;
    net_buffer_t nb;
    char recvbuf[MAX_LINE_LENGTH + 1];
    char *words[MAX_LINE_LENGTH];
    int nwords;
    State state;
    struct utsname my_uname;
    // TODO: Add additional fields as necessary
    char *sender_user;
    user_list_t ul;
} smtp_state;

static void handle_client(int fd);

int main(int argc, char *argv[]) {

    if (argc != 2) {
        fprintf(stderr, "Invalid arguments. Expected: %s <port>\n", argv[0]);
        return 1;
    }

    run_server(argv[1], handle_client);

    return 0;
}

// syntax_error returns
//   -1 if the server should exit
//    1  otherwise
int syntax_error(smtp_state *ms) {
    if (send_formatted(ms->fd, "501 %s\r\n", "Syntax error in parameters or arguments") <= 0)
        return -1;
    return 1;
}

// checkstate returns
//   -1 if the server should exit
//    0 if the server is in the appropriate state
//    1 if the server is not in the appropriate state
int checkstate(smtp_state *ms, State s) {
    if (ms->state != s) {
        if (send_formatted(ms->fd, "503 %s\r\n", "Bad sequence of commands") <= 0)
            return -1;
        return 1;
    }
    return 0;
}

// All the functions that implement a single command return
//   -1 if the server should exit
//    0 if the command was successful
//    1 if the command was unsuccessful

int do_quit(smtp_state *ms) {
    dlog("Executing quit\n");
    // TODO: Implement this function
    if (send_formatted(ms->fd, "221 Service closing transmission channel\r\n") > 0) {
        return -1;
    }
    return 1;
}

int do_helo(smtp_state *ms) {
    dlog("Executing helo\n");
    // TODO: Implement this function
    if (send_formatted(ms->fd, "250 %s\r\n", ms->my_uname.nodename) <= 0) {
        return -1;
    }
    ms->state = Greeted;
    return 0;
}

int do_rset(smtp_state *ms) {
    dlog("Executing rset\n");
    // TODO: Implement this function
    free(ms->sender_user);
    user_list_destroy(ms->ul);
    ms->sender_user = NULL;
    ms->ul = user_list_create();

    ms->state = Greeted;

    if (send_formatted(ms->fd, "250 %s\r\n", "State reset") <= 0) {
        return -1;
    }
    return 0;
}

int do_mail(smtp_state *ms) {
    dlog("Executing mail\n");
    // TODO: Implement this function
    int res = checkstate(ms, Greeted);
    if (res != 0) {
        return res;
    }
    if (ms->sender_user) {
        free(ms->sender_user);
    }
    if (strlen(ms->words[1]) > 5) {
        char *tmp = strdup(&ms->words[1][5]);
        char *tmp_removed_brackets = trim_angle_brackets(tmp);
        ms->sender_user = strdup(tmp_removed_brackets);
        free(tmp);
        ms->state = Receiver_Ready;
        if (send_formatted(ms->fd, "250 Requested mail action ok, completed\r\n") > 0) {
            return 0;
        }
    }
    return 1;
}

int do_rcpt(smtp_state *ms) {
    dlog("Executing rcpt\n");
    // TODO: Implement this function
    int res;
    if (ms->ul == NULL) {
        res = checkstate(ms, Receiver_Ready);
    } else {
        res = checkstate(ms, Send_Ready);
    }

    if (res != 0) {
        return res;
    }

    if (strlen(ms->words[1]) > 3) {
        char *rcpt_name = &ms->words[1][3];
        rcpt_name = trim_angle_brackets(rcpt_name);
        if (is_valid_user(rcpt_name, NULL)) {
            user_list_add(&ms->ul, rcpt_name);
            if (send_formatted(ms->fd, "250 Requested mail action ok, completed\r\n") <= 0) {
                return -1;
            }
            ms->state = Send_Ready;
            return 0;
        }
        if (send_formatted(ms->fd, "550 No such user - %s\r\n", rcpt_name) <= 0) {
            return -1;
        }
    }

    return 1;
}

int do_data(smtp_state *ms) {
    dlog("Executing data\n");
    // TODO: Implement this function
    int res = checkstate(ms, Send_Ready);
    if (res != 0) {
        return res;
    }

    if (send_formatted(ms->fd, "354 %s\r\n", "Waiting for data, finish with <CR><LF>.<CR><LF>") <= 0) {
        return -1;
    }

    char template[] = "./fileXXXXXX";
    int fd = mkstemp(template);

    size_t len;

    while ((len = nb_read_line(ms->nb, ms->recvbuf)) >= 0) {
        // if (ms->recvbuf[0] == '.' && ms->recvbuf[1] == '\n') {
        //     break;
        // }
        if (memcmp(ms->recvbuf, ".", 1) == 0) {
            break;
        }
        write(fd, ms->recvbuf, len);
    }
    close(fd);

    save_user_mail(template, ms->ul);

    if (send_formatted(ms->fd, "250 %s\r\n", "Requested mail action ok, completed") <= 0) {
        return -1;
    }

    return 0;
}

int do_noop(smtp_state *ms) {
    dlog("Executing noop\n");
    // TODO: Implement this function
    if (send_formatted(ms->fd, "250 %s\r\n", "OK (noop)") <= 0) {
        return -1;
    }
    return 0;
}

int do_vrfy(smtp_state *ms) {
    dlog("Executing vrfy\n");
    // TODO: Implement this function
    if (is_valid_user(ms->words[1], NULL)) {
        if (send_formatted(ms->fd, "250 User found - %s\r\n", ms->words[1]) <= 0) {
            return -1;
        }
        return 0;
    }
    if (send_formatted(ms->fd, "550 No such user - %s\r\n", ms->words[1]) <= 0) {
        return -1;
    }
    return 0;
}

void handle_client(int fd) {

    size_t len;
    smtp_state mstate, *ms = &mstate;

    ms->fd = fd;
    ms->nb = nb_create(fd, MAX_LINE_LENGTH);
    ms->state = Undefined;
    uname(&ms->my_uname);
    // Initialization of student fields
    ms->sender_user = NULL;
    ms->ul = user_list_create();

    if (send_formatted(fd, "220 %s Service ready\r\n", ms->my_uname.nodename) <= 0)
        return;

    while ((len = nb_read_line(ms->nb, ms->recvbuf)) >= 0) {
        if (ms->recvbuf[len - 1] != '\n') {
            // command line is too long, stop immediately
            send_formatted(fd, "500 Syntax error, command unrecognized\r\n");
            break;
        }
        if (strlen(ms->recvbuf) < len) {
            // received null byte somewhere in the string, stop immediately.
            send_formatted(fd, "500 Syntax error, command unrecognized\r\n");
            break;
        }

        // Remove CR, LF and other space characters from end of buffer
        while (isspace(ms->recvbuf[len - 1]))
            ms->recvbuf[--len] = 0;

        dlog("Command is %s\n", ms->recvbuf);

        // Split the command into its component "words"
        ms->nwords = split(ms->recvbuf, ms->words);
        char *command = ms->words[0];

        if (!strcasecmp(command, "QUIT")) {
            if (do_quit(ms) == -1)
                break;
        } else if (!strcasecmp(command, "HELO") || !strcasecmp(command, "EHLO")) {
            if (do_helo(ms) == -1)
                break;
        } else if (!strcasecmp(command, "MAIL")) {
            if (do_mail(ms) == -1)
                break;
        } else if (!strcasecmp(command, "RCPT")) {
            if (do_rcpt(ms) == -1)
                break;
        } else if (!strcasecmp(command, "DATA")) {
            if (do_data(ms) == -1)
                break;
        } else if (!strcasecmp(command, "RSET")) {
            if (do_rset(ms) == -1)
                break;
        } else if (!strcasecmp(command, "NOOP")) {
            if (do_noop(ms) == -1)
                break;
        } else if (!strcasecmp(command, "VRFY")) {
            if (do_vrfy(ms) == -1)
                break;
        } else if (!strcasecmp(command, "EXPN") ||
                   !strcasecmp(command, "HELP")) {
            dlog("Command not implemented \"%s\"\n", command);
            if (send_formatted(fd, "502 Command not implemented\r\n") <= 0)
                break;
        } else {
            // invalid command
            dlog("Illegal command \"%s\"\n", command);
            if (send_formatted(fd, "500 Syntax error, command unrecognized\r\n") <= 0)
                break;
        }
    }

    nb_destroy(ms->nb);
}
