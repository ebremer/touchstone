<?php

declare(strict_types=1);

namespace Example\LwsDriver;

/**
 * The defaults: every operation but configure answers unsupported until you implement it. Extend it,
 * and {@see AdapterRunner} lists in the hello exactly the operations whose methods your class declares.
 */
abstract class AbstractAdapter implements LwsDriverAdapter
{
    public function discoverStorage(\stdClass $args): array|\stdClass
    {
        throw AdapterError::unsupported('discover_storage');
    }

    public function head(\stdClass $args): array|\stdClass
    {
        throw AdapterError::unsupported('head');
    }

    public function read(\stdClass $args): array|\stdClass
    {
        throw AdapterError::unsupported('read');
    }

    public function listContainer(\stdClass $args): array|\stdClass
    {
        throw AdapterError::unsupported('list_container');
    }

    public function create(\stdClass $args): array|\stdClass
    {
        throw AdapterError::unsupported('create');
    }

    public function createContainer(\stdClass $args): array|\stdClass
    {
        throw AdapterError::unsupported('create_container');
    }

    public function update(\stdClass $args): array|\stdClass
    {
        throw AdapterError::unsupported('update');
    }

    public function patch(\stdClass $args): array|\stdClass
    {
        throw AdapterError::unsupported('patch');
    }

    public function delete(\stdClass $args): array|\stdClass
    {
        throw AdapterError::unsupported('delete');
    }

    public function readLinkset(\stdClass $args): array|\stdClass
    {
        throw AdapterError::unsupported('read_linkset');
    }

    public function updateLinkset(\stdClass $args): array|\stdClass
    {
        throw AdapterError::unsupported('update_linkset');
    }

    public function patchLinkset(\stdClass $args): array|\stdClass
    {
        throw AdapterError::unsupported('patch_linkset');
    }

    public function move(\stdClass $args): array|\stdClass
    {
        throw AdapterError::unsupported('move');
    }

    public function requestAccess(\stdClass $args): array|\stdClass
    {
        throw AdapterError::unsupported('request_access');
    }

    public function getAccessRequest(\stdClass $args): array|\stdClass
    {
        throw AdapterError::unsupported('get_access_request');
    }

    public function listAccessRequests(\stdClass $args): array|\stdClass
    {
        throw AdapterError::unsupported('list_access_requests');
    }

    public function cancelAccessRequest(\stdClass $args): array|\stdClass
    {
        throw AdapterError::unsupported('cancel_access_request');
    }

    public function grantAccess(\stdClass $args): array|\stdClass
    {
        throw AdapterError::unsupported('grant_access');
    }

    public function getAccessGrant(\stdClass $args): array|\stdClass
    {
        throw AdapterError::unsupported('get_access_grant');
    }

    public function listAccessGrants(\stdClass $args): array|\stdClass
    {
        throw AdapterError::unsupported('list_access_grants');
    }

    public function revokeAccessGrant(\stdClass $args): array|\stdClass
    {
        throw AdapterError::unsupported('revoke_access_grant');
    }

    public function subscribe(\stdClass $args): array|\stdClass
    {
        throw AdapterError::unsupported('subscribe');
    }

    public function listSubscriptions(\stdClass $args): array|\stdClass
    {
        throw AdapterError::unsupported('list_subscriptions');
    }

    public function getSubscription(\stdClass $args): array|\stdClass
    {
        throw AdapterError::unsupported('get_subscription');
    }

    public function unsubscribe(\stdClass $args): array|\stdClass
    {
        throw AdapterError::unsupported('unsubscribe');
    }

    public function verifyNotification(\stdClass $args): array|\stdClass
    {
        throw AdapterError::unsupported('verify_notification');
    }

    public function listTypes(\stdClass $args): array|\stdClass
    {
        throw AdapterError::unsupported('list_types');
    }

    public function search(\stdClass $args): array|\stdClass
    {
        throw AdapterError::unsupported('search');
    }
}
