Pass ports 50051, 50052, 50053 etc... to server args[] to run servers
adjust vars in coordinator to change input

proto is attached, gradle build should generate classes

Test results are consistent provided PC is not doing some heavy background work (like YT/Stream in background)
Also make sure logging is off, including built-in grpc logging (especially if it would happen each loop)
