// -*- mode: js; indent-tabs-mode: nil; js-basic-offset: 4 -*-
//
// This file is part of ThingEngine
//
// Copyright 2015 Giovanni Campagna <gcampagn@cs.stanford.edu>
//
// See COPYING for details
"use strict";

const Q = require('q');
const fs = require('fs');
const net = require('net');

const JsonDatagramSocket = require('./json_datagram_socket');

module.exports = class ControlChannel {
    constructor() {
        this._server = net.createServer({ allowHalfOpen: true });

        this._server.on('connection', this._handleConnection.bind(this));
        this._closeOk = true;
        this._socket = null;
        this._partialMessage = '';
    }

    open() {
        var controlPath = platform.getWritableDir() + '/control';
        try {
            fs.unlinkSync(controlPath);
        } catch(e) {
            if (e.code != 'ENOENT')
                throw e;
        }
        return Q.ninvoke(this._server, 'listen', controlPath);
    }

    close() {
        this._closeOk = true;
        this._socket.end();
        this._socket = null;
        this._server.close();
    }

    _handleConnection(socket) {
        if (this._socket != null) {
            console.error('Unexpected new connection on communication channel');
            this._socket.end();
        }

        var jsonSocket = new JsonDatagramSocket(socket, socket, platform.encoding);
        this._socket = jsonSocket;
        this._socket.on('end', function() {
            if (this._socket != jsonSocket)
                return;

            if (!this._closeOk) {
                console.error('Unexpected closure on communication channel');
                this._socket.end();
            }

            this._socket = null;
        }.bind(this));
        this._socket.on('data', function(data) {
            if (jsonSocket != this._socket) // robustness
                return;

            this._handleMessage(data);
        }.bind(this));
        this._closeOk = false;
    }

    _handleMessage(msg) {
        if (!msg.method || !msg.args) {
            console.error('Malformed message on control channel');
            return;
        }

        Q.mapply(this, msg.method, msg.args).then(function(result) {
            if (this._socket && msg.replyId)
                return Q.ninvoke(this._socket, 'write', {id:msg.replyId, reply:result});
        }.bind(this), function(error) {
            console.error('Error in async control call', error.message);
            console.error(error.stack);
            if (this._socket)
                return Q.ninvoke(this._socket, 'write', {id:msg.replyId, error:error.message});
        }.bind(this)).done();
    }
}

